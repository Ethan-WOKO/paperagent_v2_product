package com.yanban.api.agent.v2.intake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V63V2TurnHistoryMigrationTest {
    @Test
    void existingRowsStayHiddenAndOnlyExplicitNewRowsAreVisible()
            throws Exception {
        String url = "jdbc:h2:mem:v63-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            execute(connection, Files.readString(Path.of(
                    "src/test/resources/db/migration-h2/"
                            + "V61__create_agent_v2_turn_intakes.sql")));
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_v2_turn_intakes(
                      user_id,session_id,client_request_id,request_sha256,
                      content_text,rag_disabled,user_message_id,turn_id,
                      status,created_at,updated_at)
                    VALUES (7,9,'historical',REPEAT('a',64),'old',FALSE,
                            11,12,'RUNNING',CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP)
                    """);
            execute(connection, Files.readString(Path.of(
                    "src/test/resources/db/migration-h2/"
                            + "V63__add_v2_turn_history_visibility.sql")));

            assertFalse(visible(connection, "historical"));
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_v2_turn_intakes(
                      user_id,session_id,client_request_id,request_sha256,
                      content_text,rag_disabled,user_message_id,turn_id,
                      status,history_visible,created_at,updated_at)
                    VALUES (7,9,'new',REPEAT('b',64),'new',FALSE,13,14,
                            'RUNNING',TRUE,CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP)
                    """);
            assertTrue(visible(connection, "new"));
            try (var result = connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM agent_v2_turn_intakes "
                            + "WHERE history_visible=TRUE")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void mainMigrationUsesAStableFalseDefaultWithoutDateBoundary()
            throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V63__add_v2_turn_history_visibility.sql"));
        assertTrue(sql.contains(
                "history_visible BOOLEAN NOT NULL DEFAULT FALSE"));
        assertFalse(sql.toLowerCase().contains("current_date"));
        assertFalse(sql.toLowerCase().contains("current_timestamp"));
    }

    private static void execute(
            java.sql.Connection connection, String sql) throws Exception {
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                connection.createStatement().execute(statement);
            }
        }
    }

    private static boolean visible(
            java.sql.Connection connection, String requestId)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT history_visible FROM agent_v2_turn_intakes "
                        + "WHERE client_request_id=?")) {
            statement.setString(1, requestId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
