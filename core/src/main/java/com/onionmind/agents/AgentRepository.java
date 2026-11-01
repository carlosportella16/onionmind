package com.onionmind.agents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@code agents} and {@code agent_runs} (design D4), plain SQL like the other modules. */
@Repository
class AgentRepository {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ObjectReader SOURCES = MAPPER.readerForListOf(AgentRun.Source.class);
    private static final String AGENT_COLUMNS = """
        id, name, instructions, topic, categories, window_days, tools, cost_profile, max_tokens_per_run, daily,
        created_at, updated_at""";
    private static final String RUN_COLUMNS = """
        id, agent_id, trigger, status, failure_reason, report, sources::text AS sources, unavailable_tools,
        tokens_used, started_at, finished_at""";

    private final JdbcTemplate jdbc;

    AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Agent> findAll() {
        return jdbc.query("SELECT " + AGENT_COLUMNS + " FROM agents ORDER BY name", AGENT);
    }

    List<Agent> findDaily() {
        return jdbc.query("SELECT " + AGENT_COLUMNS + " FROM agents WHERE daily ORDER BY id", AGENT);
    }

    Optional<Agent> find(long id) {
        return jdbc.query("SELECT " + AGENT_COLUMNS + " FROM agents WHERE id = ?", AGENT, id).stream().findFirst();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the name is taken */
    Agent insert(Agent a) {
        return jdbc.queryForObject("""
            INSERT INTO agents (name, instructions, topic, categories, window_days, tools, cost_profile,
                                max_tokens_per_run, daily)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING\s""" + AGENT_COLUMNS, AGENT, a.name(), a.instructions(), a.topic(), array(a.categories()),
            a.windowDays(), array(a.tools()), a.costProfile(), a.maxTokensPerRun(), a.daily());
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the name is taken */
    Optional<Agent> update(long id, Agent a) {
        return jdbc.query("""
            UPDATE agents SET name = ?, instructions = ?, topic = ?, categories = ?, window_days = ?, tools = ?,
                              cost_profile = ?, max_tokens_per_run = ?, daily = ?, updated_at = now()
            WHERE id = ?
            RETURNING\s""" + AGENT_COLUMNS, AGENT, a.name(), a.instructions(), a.topic(), array(a.categories()),
            a.windowDays(), array(a.tools()), a.costProfile(), a.maxTokensPerRun(), a.daily(), id).stream().findFirst();
    }

    /** Its runs go with it (ON DELETE CASCADE). */
    boolean delete(long id) {
        return jdbc.update("DELETE FROM agents WHERE id = ?", id) > 0;
    }

    long startRun(long agentId, String trigger) {
        return jdbc.queryForObject("INSERT INTO agent_runs (agent_id, trigger, status) VALUES (?, ?, 'running') RETURNING id",
            Long.class, agentId, trigger);
    }

    /** Empty when the agent already has a scheduled run today (UTC) — the partial unique index decides. */
    Optional<Long> startDailyRun(long agentId) {
        return jdbc.queryForList("""
            INSERT INTO agent_runs (agent_id, trigger, status) VALUES (?, 'daily', 'running')
            ON CONFLICT DO NOTHING
            RETURNING id""", Long.class, agentId).stream().findFirst();
    }

    void finishRun(long runId, AgentRunner.Outcome o) {
        jdbc.update("""
            UPDATE agent_runs SET status = ?, failure_reason = ?, report = ?, sources = ?::jsonb,
                                  unavailable_tools = ?, tokens_used = ?, finished_at = now()
            WHERE id = ?""", o.status(), o.failureReason(), o.report(), MAPPER.writeValueAsString(o.sources()),
            array(o.unavailableTools()), o.tokensUsed(), runId);
    }

    /** Newest first. */
    List<AgentRun> runs(long agentId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM agent_runs WHERE agent_id = ? ORDER BY started_at DESC, id DESC",
            RUN, agentId);
    }

    Optional<AgentRun> run(long agentId, long runId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM agent_runs WHERE agent_id = ? AND id = ?", RUN, agentId, runId)
            .stream().findFirst();
    }

    private static final RowMapper<Agent> AGENT = (rs, n) -> new Agent(rs.getLong("id"), rs.getString("name"),
        rs.getString("instructions"), rs.getString("topic"), list(rs.getArray("categories")), rs.getInt("window_days"),
        list(rs.getArray("tools")), rs.getString("cost_profile"), rs.getInt("max_tokens_per_run"),
        rs.getBoolean("daily"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<AgentRun> RUN = (rs, n) -> {
        Instant started = instant(rs.getTimestamp("started_at"));
        Instant finished = instant(rs.getTimestamp("finished_at"));
        return new AgentRun(rs.getLong("id"), rs.getLong("agent_id"), rs.getString("trigger"), rs.getString("status"),
            rs.getString("failure_reason"), rs.getString("report"), SOURCES.readValue(rs.getString("sources")),
            list(rs.getArray("unavailable_tools")), rs.getInt("tokens_used"), started, finished,
            finished == null ? null : finished.toEpochMilli() - started.toEpochMilli());
    };

    private static String[] array(List<String> values) {
        return values.toArray(String[]::new);
    }

    private static List<String> list(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
