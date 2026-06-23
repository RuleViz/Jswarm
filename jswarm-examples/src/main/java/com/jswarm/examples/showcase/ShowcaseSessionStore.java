// SQLite 会话持久化
package com.jswarm.examples.showcase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jswarm.adapter.lc4j.run.ChatMessageCodec;
import com.jswarm.core.SwarmContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ShowcaseSessionStore implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;

    public ShowcaseSessionStore(Path dbPath) throws Exception {
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }
        jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection connection = openConnection()) {
            initSchema(connection);
        }
    }

    private Connection openConnection() throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA journal_mode=WAL");
        }
        return connection;
    }

    private void initSchema(Connection connection) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS showcase_session (
                        session_id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        current_agent_id TEXT NOT NULL,
                        entry_hook_fired INTEGER NOT NULL,
                        context_json TEXT NOT NULL,
                        history_json TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
        }
    }

    public Optional<ShowcaseSession> load(String sessionId, String entryAgentId) {
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT user_id, current_agent_id, entry_hook_fired, context_json, history_json "
                             + "FROM showcase_session WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                SwarmContext ctx = decodeContext(rs.getString("context_json"));
                ShowcaseSession session = new ShowcaseSession(sessionId, entryAgentId, ctx);
                session.setCurrentAgentId(rs.getString("current_agent_id"));
                session.setEntryHookFired(rs.getInt("entry_hook_fired") == 1);
                session.setHistory(ChatMessageCodec.decode(rs.getString("history_json")));
                return Optional.of(session);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load session: " + sessionId, e);
        }
    }

    public void save(ShowcaseSession session) {
        try (Connection connection = openConnection()) {
            String userId = session.context().get("user_id", String.class);
            if (userId == null) {
                userId = "u001";
            }
            String contextJson = encodeContext(session.context());
            String historyJson = ChatMessageCodec.encode(session.history());
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO showcase_session
                    (session_id, user_id, current_agent_id, entry_hook_fired, context_json, history_json, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        user_id = excluded.user_id,
                        current_agent_id = excluded.current_agent_id,
                        entry_hook_fired = excluded.entry_hook_fired,
                        context_json = excluded.context_json,
                        history_json = excluded.history_json,
                        updated_at = excluded.updated_at
                    """)) {
                ps.setString(1, session.sessionId());
                ps.setString(2, userId);
                ps.setString(3, session.currentAgentId());
                ps.setInt(4, session.entryHookFired() ? 1 : 0);
                ps.setString(5, contextJson);
                ps.setString(6, historyJson);
                ps.setString(7, Instant.now().toString());
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save session: " + session.sessionId(), e);
        }
    }

    public void delete(String sessionId) {
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM showcase_session WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete session: " + sessionId, e);
        }
    }

    private static String encodeContext(SwarmContext ctx) throws Exception {
        return MAPPER.writeValueAsString(ctx.asMap());
    }

    private static SwarmContext decodeContext(String json) throws Exception {
        SwarmContext ctx = new SwarmContext();
        Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {
        });
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if ("total_spent".equals(entry.getKey()) && value instanceof Number number) {
                ctx.put(entry.getKey(), number.intValue());
            } else {
                ctx.put(entry.getKey(), value);
            }
        }
        return ctx;
    }

    @Override
    public void close() {
    }
}
