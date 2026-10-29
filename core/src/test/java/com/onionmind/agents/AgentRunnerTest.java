package com.onionmind.agents;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.AllProvidersExhaustedException;
import com.onionmind.ai.Report;
import com.onionmind.ai.TaskContext;
import com.onionmind.search.PassageRetriever;
import com.onionmind.search.PassageRetriever.Retrieval;
import com.onionmind.search.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs (agents/agent-runs) against real Postgres full-text and change rows, with a scripted
 * report. The graph is off in this context, so the real EntitiesTool is unavailable; the
 * three-tool run uses a stand-in for it (EntitiesToolTest covers the real one on Neo4j).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AgentRunnerTest {

    private static final Duration INTERACTIVE_TIMEOUT = Duration.ofMillis(400);
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AgentRepository repo;
    @Autowired
    private PassageRetriever retriever;
    @Autowired
    private SearchTool search;
    @Autowired
    private ChangesTool changes;
    @Autowired
    private EntitiesTool entities;

    private ScriptedReport ai;
    private CountingTool countedChanges;
    private AgentRunner runner;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agents WHERE name <> 'Research Agent'");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        long market = insertPage("http://passports.onion/", "Marketplace selling fake passports and forged identity documents.");
        insertPage("http://forum.onion/", "A forum where members trade fake passports stories and gossip.");
        insertChange(market, "http://passports.onion/", "marketplace", "Now also sells driving licences.", "1 day");
        insertChange(market, "http://passports.onion/", "marketplace", "Old change outside the window.", "20 days");
        ai = new ScriptedReport();
        countedChanges = new CountingTool(changes);
        runner = runner(List.of(search, countedChanges, entities));
    }

    private AgentRunner runner(List<AgentTool> tools) {
        return new AgentRunner(repo, retriever, ai, tools, INTERACTIVE_TIMEOUT, BATCH_TIMEOUT);
    }

    private long insertPage(String url, String text) {
        return jdbc.queryForObject("""
            INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
            VALUES (?, 'tor', ?, ?, ?, 1) RETURNING id
            """, Long.class, url, "<html>" + text + "</html>", text, Integer.toHexString(text.hashCode()));
    }

    private void insertChange(long pageId, String url, String category, String summary, String age) {
        jdbc.update("""
            INSERT INTO page_changes (page_id, url, from_version, to_version, category_to, status, detected_at, summary)
            VALUES (?, ?, 1, (SELECT coalesce(max(to_version), 1) + 1 FROM page_changes WHERE page_id = ?), ?,
                    'summarized', now() - ?::interval, jsonb_build_object('text', ?::text, 'confidence', 0.9))
            """, pageId, url, pageId, category, age, summary);
    }

    private Agent agent(String topic, List<String> tools, String profile, int maxTokens) {
        Agent a = new Agent(null, "agent-" + System.nanoTime(), "Be brief.", topic, List.of(), 7, tools, profile,
            maxTokens, false, null, null);
        return repo.insert(a);
    }

    @Test
    void agentWithThreeToolsReportsFromAllOfThemAndPersistsTheRun() {
        AgentTool fakeEntities = new AgentTool() {
            public String name() {
                return "entities";
            }

            public List<AgentRun.Source> collect(Agent agent, Retrieval retrieval) {
                assertThat(retrieval.results()).extracting(SearchResult::url).contains("http://passports.onion/");
                return List.of(new AgentRun.Source("E1", "entities", "/api/entities/person/ana", "Entity: Ana (person)"));
            }
        };
        Agent agent = agent("fake passports", Agent.TOOLS, "batch", 8000);
        ai.report = sources -> new Report("Passports [S1], licences [C1], Ana [E1].", List.of("S1", "C1", "E1"), 0.9);

        AgentRun run = runner(List.of(search, changes, fakeEntities)).run(agent);

        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(run.trigger()).isEqualTo("manual");
        assertThat(run.report()).isEqualTo("Passports [S1], licences [C1], Ana [E1].");
        assertThat(run.sources()).extracting(AgentRun.Source::tool).containsExactly("search", "changes", "entities");
        assertThat(run.sources().get(1).text()).contains("Now also sells driving licences.");
        assertThat(run.tokensUsed()).isPositive().isLessThanOrEqualTo(8000);
        assertThat(run.durationMillis()).isNotNull().isNotNegative();
        assertThat(ai.sent.getFirst().keySet()).containsExactly("S1", "S2", "C1", "E1");
        assertThat(ai.sent.getFirst().values()).noneMatch(t -> t.contains("Old change outside the window."));
        assertThat(ai.contexts).singleElement().satisfies(ctx -> {
            assertThat(ctx.type()).isEqualTo(TaskContext.TaskType.REPORT);
            assertThat(ctx.interactive()).isFalse();
        });
        assertThat(repo.runs(agent.id())).singleElement().isEqualTo(run);
        assertThat(repo.run(agent.id(), run.id())).contains(run);
    }

    @Test
    void disabledToolIsNotQueried() {
        Agent agent = agent("fake passports", List.of("search"), "interactive", 8000);
        ai.report = sources -> new Report("Passports [S1].", List.of("S1"), 0.9);

        AgentRun run = runner.run(agent);

        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(countedChanges.calls).hasValue(0);
        assertThat(ai.sent.getFirst().keySet()).allMatch(id -> id.startsWith("S"));
        assertThat(ai.contexts.getFirst().interactive()).isTrue();
    }

    @Test
    void changesAloneSkipTheSearch() {
        Agent agent = agent("anything at all", List.of("changes"), "batch", 8000);
        ai.report = sources -> new Report("Licences [C1].", List.of("C1"), 0.9);

        AgentRun run = runner.run(agent);

        assertThat(run.sources()).extracting(AgentRun.Source::id).containsExactly("C1");
        assertThat(ai.sent.getFirst().keySet()).containsExactly("C1");
    }

    @Test
    void changesAreFilteredByTheAgentsCategories() {
        Agent forums = repo.insert(new Agent(null, "forums-" + System.nanoTime(), "", "anything", List.of("forum"), 7,
            List.of("changes"), "batch", 8000, false, null, null));

        assertThat(runner.run(forums).report()).isEqualTo(Report.NO_MATERIAL);
        assertThat(changes.collect(new Agent(null, "m", "", "t", List.of("marketplace", "forum"), 30, List.of("changes"),
            "batch", 8000, false, null, null), null)).extracting(AgentRun.Source::id).containsExactly("C1", "C2");
    }

    @Test
    void withTheGraphOffEntitiesIsRecordedAsUnavailableAndTheRunGoesOn() {
        Agent agent = agent("fake passports", List.of("search", "entities"), "batch", 8000);
        ai.report = sources -> new Report("Passports [S1].", List.of("S1"), 0.9);

        AgentRun run = runner.run(agent);

        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(run.unavailableTools()).containsExactly("entities");
    }

    @Test
    void nothingFoundIsTheNoMaterialReportWithoutCallingTheModel() {
        Agent agent = agent("carrot cake recipe", List.of("search", "entities"), "batch", 8000);

        AgentRun run = runner.run(agent);

        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(run.report()).isEqualTo(Report.NO_MATERIAL);
        assertThat(run.sources()).isEmpty();
        assertThat(run.tokensUsed()).isZero();
        assertThat(ai.contexts).isEmpty();
    }

    @Test
    void ceilingBelowTheMinimalPromptFailsWithBudgetWithoutCallingTheModel() {
        Agent agent = agent("fake passports", List.of("search", "changes"), "batch", 50);

        AgentRun run = runner.run(agent);

        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.failureReason()).isEqualTo("budget");
        assertThat(run.report()).isNull();
        assertThat(ai.contexts).isEmpty();
    }

    @Test
    void contextOverTheCeilingIsCutKeepingTheBestRankedSources() {
        // instructions + topic + the first passage fit; the second passage and the change do not
        int fixed = ("Be brief.".length() + 3) / 4 + ("fake passports".length() + 3) / 4;
        int firstPassage = (search.collect(null, retriever.retrieve("fake passports", null, AgentRunner.TOP_K,
            Integer.MAX_VALUE)).getFirst().text().length() + 3) / 4;
        Agent agent = agent("fake passports", List.of("search", "changes"), "batch",
            AgentRunner.RESERVED_TOKENS + fixed + firstPassage + 5);
        ai.report = sources -> new Report("Passports [S1].", List.of("S1"), 0.9);

        AgentRun run = runner.run(agent);

        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(ai.sent.getFirst().keySet()).containsExactly("S1");
    }

    @Test
    void lateReportFailsWithTimeoutWithinTheProfileDeadline() {
        Agent agent = agent("fake passports", List.of("search"), "interactive", 8000);
        ai.report = sources -> {
            sleep(INTERACTIVE_TIMEOUT.multipliedBy(5));
            return new Report("too late [S1]", List.of("S1"), 0.9);
        };

        long start = System.nanoTime();
        AgentRun run = runner.run(agent);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(INTERACTIVE_TIMEOUT.multipliedBy(3));
        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.failureReason()).isEqualTo("timeout");
        assertThat(run.finishedAt()).isNotNull();
    }

    @Test
    void reportFailingOnEveryProviderFailsWithError() {
        Agent agent = agent("fake passports", List.of("search"), "batch", 8000);
        ai.report = sources -> {
            throw new AllProvidersExhaustedException("all providers failed for REPORT", false, null);
        };

        AgentRun run = runner.run(agent);

        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.failureReason()).isEqualTo("error");
    }

    @Test
    void dailyJobRunsEachDailyAgentOnceADayAsBatch() {
        Agent daily = repo.insert(new Agent(null, "daily-" + System.nanoTime(), "", "fake passports", List.of(), 7,
            List.of("search"), "interactive", 8000, true, null, null));
        ai.report = sources -> new Report("Passports [S1].", List.of("S1"), 0.9);
        var job = new DailyAgentJob(repo, runner);

        job.run();
        job.run();

        assertThat(repo.runs(daily.id())).singleElement().satisfies(run -> {
            assertThat(run.trigger()).isEqualTo("daily");
            assertThat(run.status()).isEqualTo("succeeded");
        });
        assertThat(ai.contexts).singleElement().satisfies(ctx -> assertThat(ctx.interactive()).isFalse());
        // the seeded Research Agent is not daily
        long seed = jdbc.queryForObject("SELECT id FROM agents WHERE name = 'Research Agent'", Long.class);
        assertThat(repo.runs(seed)).isEmpty();
    }

    @Test
    void runEndpointsAre404ForAnUnknownAgentOrRun() {
        var controller = new AgentController(repo, runner, 4000);
        Agent agent = agent("fake passports", List.of("search"), "batch", 8000);
        ai.report = sources -> new Report("Passports [S1].", List.of("S1"), 0.9);

        AgentRun run = controller.run(agent.id()).getBody();

        assertThat(controller.run(agent.id(), run.id()).getBody()).isEqualTo(run);
        assertThat(controller.runs(agent.id()).getBody()).containsExactly(run);
        assertThat(controller.run(-1L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.run(agent.id(), -1L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CountingTool implements AgentTool {
        final AgentTool tool;
        final AtomicInteger calls = new AtomicInteger();

        CountingTool(AgentTool tool) {
            this.tool = tool;
        }

        public String name() {
            return tool.name();
        }

        public List<AgentRun.Source> collect(Agent agent, Retrieval retrieval) {
            calls.incrementAndGet();
            return tool.collect(agent, retrieval);
        }
    }

    /** Only {@code report} is used by a run; everything else fails loudly. */
    private static final class ScriptedReport implements AIOrchestrator {
        volatile Function<SequencedMap<String, String>, Report> report = s -> {
            throw new IllegalStateException("report not scripted");
        };
        final List<TaskContext> contexts = new CopyOnWriteArrayList<>();
        final List<SequencedMap<String, String>> sent = new CopyOnWriteArrayList<>();

        @Override
        public Report report(String instructions, String topic, SequencedMap<String, String> sources, TaskContext ctx) {
            contexts.add(ctx);
            sent.add(sources);
            return report.apply(sources);
        }

        public com.onionmind.ai.Summary summarize(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        public com.onionmind.ai.Classification classify(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        public com.onionmind.ai.Translation translate(String text, String targetLang, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        public com.onionmind.ai.LanguageDetection detectLanguage(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        public com.onionmind.ai.EntityExtraction extractEntities(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        public com.onionmind.ai.QueryRewrite rewriteQuery(String question, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        public com.onionmind.ai.Answer answer(String question, List<String> sources, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        public com.onionmind.ai.Embedding embed(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }
    }
}
