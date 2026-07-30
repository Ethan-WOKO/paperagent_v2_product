package com.yanban.api.agent.v2.intake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V61NaturalLanguageTurnMigrationTest {
    @Test
    void createsBoundedIdempotencyAndStatusAuthority() throws Exception {
        String url = "jdbc:h2:mem:v61-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            String sql = Files.readString(
                    Path.of("src/test/resources/db/migration-h2/"
                            + "V61__create_agent_v2_turn_intakes.sql"),
                    StandardCharsets.UTF_8);
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) {
                    connection.createStatement().execute(statement);
                }
            }
            insert(connection, "request-1", "RUNNING", null);
            assertEquals(1, count(connection));
            assertThrows(SQLException.class,
                    () -> insert(connection, "request-1", "RUNNING", null));
            assertThrows(SQLException.class,
                    () -> insert(connection, "request-2", "UNKNOWN", null));
        }
    }

    private static void insert(
            java.sql.Connection connection,
            String requestId,
            String status,
            String planId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO agent_v2_turn_intakes(
                  user_id, session_id, client_request_id, request_sha256,
                  content_text, rag_disabled, user_message_id, turn_id,
                  plan_id, status, created_at, updated_at)
                VALUES (1, 2, ?, ?, 'question', FALSE, 3, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, requestId);
            statement.setString(2, "a".repeat(64));
            statement.setLong(3, requestId.endsWith("1") ? 4 : 5);
            statement.setString(4, planId);
            statement.setString(5, status);
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(6, now);
            statement.setTimestamp(7, now);
            statement.executeUpdate();
        }
    }

    private static int count(java.sql.Connection connection)
            throws SQLException {
        try (var result = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM agent_v2_turn_intakes")) {
            result.next();
            return result.getInt(1);
        }
    }
}
