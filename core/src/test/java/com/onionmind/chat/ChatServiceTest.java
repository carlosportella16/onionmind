package com.onionmind.chat;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.AllProvidersExhaustedException;
import com.onionmind.ai.Answer;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.EntityExtraction;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.ai.QueryRewrite;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import com.onionmind.search.PassageRetriever;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The chat pipeline against real Postgres full-text (embedding is off in this context, so
 * retrieval is full-text only) and a scripted orchestrator — the vector path and the real
 * orchestrator are covered by PassageRetrieverTest, DefaultAIOrchestratorTest and the
 * Fase 5 acceptance scenarios.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChatServiceTest {

    private static final Duration REWRITE_TIMEOUT = Duration.ofMillis(300);
    private static final Duration ANSWER_TIMEOUT = Duration.ofMillis(500);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PassageRetriever retriever;

    private ScriptedOrchestrator ai;
    private ChatService chat;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        insertPage("http://passports.onion/", "Marketplace selling fake passports and forged identity documents.");
        insertPage("http://forum.onion/", "A forum where members trade fake passports stories and gossip.");
        ai = new ScriptedOrchestrator();
        chat = new ChatService(retriever, ai, 8, 12000, REWRITE_TIMEOUT, ANSWER_TIMEOUT);
    }

    private void insertPage(String url, String text) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
            VALUES (?, 'tor', ?, ?, ?, 1)
            """, url, "<html>" + text + "</html>", text, Integer.toHexString(text.hashCode()));
    }

    @Test
    void portugueseQuestionIsAnsweredFromTheEnglishPageItsRewriteFinds() {
        ai.rewrite = q -> new QueryRewrite("forged documents", 0.9);
        ai.answer = (q, sources) -> new Answer("The marketplace sells them [S1].", List.of("S1"), 0.9);

        ChatResponse response = chat.ask("quais mercados vendem documentos falsos?");

        assertThat(response.degraded()).isFalse();
        assertThat(response.answer()).isEqualTo("The marketplace sells them [S1].");
        assertThat(response.sources()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("S1");
            assertThat(s.url()).isEqualTo("http://passports.onion/");
            assertThat(s.snippet()).contains("forged identity documents");
        });
        assertThat(response.results()).extracting(SearchResult::url).containsExactly("http://passports.onion/");
        assertThat(ai.sources.getFirst()).singleElement().asString()
            .startsWith("URL: http://passports.onion/\n").contains("forged identity documents");
        assertThat(ai.contexts).extracting(TaskContext::type)
            .containsExactly(TaskContext.TaskType.REWRITE_QUERY, TaskContext.TaskType.ANSWER);
        assertThat(ai.contexts).allMatch(TaskContext::interactive);
    }

    @Test
    void sourcesAreOnlyThePassagesTheAnswerCites() {
        ai.rewrite = q -> new QueryRewrite("fake passports", 0.9);
        ai.answer = (q, sources) -> new Answer("Only the forum talks about it [S2].", List.of("S2"), 0.9);

        ChatResponse response = chat.ask("fake passports");

        assertThat(response.results()).hasSize(2);
        assertThat(response.sources()).extracting(ChatResponse.Source::id, ChatResponse.Source::url)
            .containsExactly(tuple("S2", response.results().get(1).url()));
    }

    @Test
    void failedRewriteFallsBackToTheOriginalQuestion() {
        ai.rewrite = q -> {
            throw new AllProvidersExhaustedException("no quota", true, null);
        };
        ai.answer = (q, sources) -> new Answer("Yes [S1].", List.of("S1"), 0.9);

        assertThat(chat.ask("fake passports").results()).hasSize(2);
    }

    @Test
    void lateRewriteFallsBackToTheOriginalQuestion() {
        ai.rewrite = q -> {
            sleep(REWRITE_TIMEOUT.multipliedBy(4));
            return new QueryRewrite("unrelated words", 0.9);
        };
        ai.answer = (q, sources) -> new Answer("Yes [S1].", List.of("S1"), 0.9);

        long start = System.nanoTime();
        ChatResponse response = chat.ask("fake passports");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(REWRITE_TIMEOUT.multipliedBy(3));
        assertThat(response.results()).hasSize(2);
    }

    @Test
    void nothingRetrievedIsTheNoAnswerSentenceWithoutCallingTheModel() {
        ai.rewrite = q -> new QueryRewrite("carrot cake recipe", 0.9);

        ChatResponse response = chat.ask("receita de bolo de cenoura");

        assertThat(response.answer()).isEqualTo(Answer.NO_ANSWER);
        assertThat(response.sources()).isEmpty();
        assertThat(response.degraded()).isFalse();
        assertThat(ai.sources).isEmpty();
    }

    @Test
    void lateAnswerIsDegradedWithTheSearchResults() {
        ai.rewrite = q -> new QueryRewrite("fake passports", 0.9);
        ai.answer = (q, sources) -> {
            sleep(ANSWER_TIMEOUT.multipliedBy(4));
            return new Answer("too late [S1]", List.of("S1"), 0.9);
        };

        long start = System.nanoTime();
        ChatResponse response = chat.ask("fake passports");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(ANSWER_TIMEOUT.multipliedBy(3));
        assertThat(response.degraded()).isTrue();
        assertThat(response.answer()).isEmpty();
        assertThat(response.sources()).isEmpty();
        assertThat(response.results()).hasSize(2);
    }

    @Test
    void answerFailingOnEveryProviderIsDegraded() {
        ai.rewrite = q -> new QueryRewrite("fake passports", 0.9);
        ai.answer = (q, sources) -> {
            throw new AllProvidersExhaustedException("all providers failed for ANSWER", false, null);
        };

        ChatResponse response = chat.ask("fake passports");

        assertThat(response.degraded()).isTrue();
        assertThat(response.results()).hasSize(2);
    }

    @Test
    void blankOrTooLongQuestionIs400WithoutCallingAnyProvider() {
        var controller = new ChatController(chat, 20);

        assertThat(controller.chat(new ChatController.ChatRequest("   ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.chat(new ChatController.ChatRequest(null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.chat(null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.chat(new ChatController.ChatRequest("x".repeat(21))).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ai.contexts).isEmpty();

        ai.rewrite = q -> new QueryRewrite("fake passports", 0.9);
        ai.answer = (q, sources) -> new Answer("Yes [S1].", List.of("S1"), 0.9);
        assertThat(controller.chat(new ChatController.ChatRequest(" fake passports ")).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(ai.questions).containsExactly("fake passports", "fake passports");
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ScriptedOrchestrator implements AIOrchestrator {
        volatile Function<String, QueryRewrite> rewrite = q -> {
            throw new IllegalStateException("rewrite not scripted");
        };
        volatile BiFunction<String, List<String>, Answer> answer = (q, s) -> {
            throw new IllegalStateException("answer not scripted");
        };
        final List<TaskContext> contexts = new CopyOnWriteArrayList<>();
        final List<String> questions = new CopyOnWriteArrayList<>();
        final List<List<String>> sources = new CopyOnWriteArrayList<>();

        @Override
        public QueryRewrite rewriteQuery(String question, TaskContext ctx) {
            contexts.add(ctx);
            questions.add(question);
            return rewrite.apply(question);
        }

        @Override
        public Answer answer(String question, List<String> sources, TaskContext ctx) {
            contexts.add(ctx);
            questions.add(question);
            this.sources.add(sources);
            return answer.apply(question, sources);
        }

        @Override
        public Summary summarize(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Classification classify(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Translation translate(String text, String targetLang, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LanguageDetection detectLanguage(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityExtraction extractEntities(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Embedding embed(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }
    }
}
