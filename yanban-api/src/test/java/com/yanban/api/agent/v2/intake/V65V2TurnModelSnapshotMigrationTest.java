package com.yanban.api.agent.v2.intake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V65V2TurnModelSnapshotMigrationTest {
    @Test
    void existingRowsRemainValidAndNewRowsCanFreezeTheirModel()
            throws Exception {
        String url = "jdbc:h2:mem:v65-" + UUID.randomUUID()
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
                            + "V65__add_v2_turn_model_snapshot.sql")));

            try (var historical = connection.createStatement().executeQuery(
                    "SELECT model_provider_snapshot,model_snapshot "
                            + "FROM agent_v2_turn_intakes "
                            + "WHERE client_request_id='historical'")) {
                historical.next();
                assertNull(historical.getString(1));
                assertNull(historical.getString(2));
            }
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_v2_turn_intakes(
                      user_id,session_id,client_request_id,request_sha256,
                      content_text,rag_disabled,user_message_id,turn_id,
                      model_provider_snapshot,model_snapshot,
                      status,created_at,updated_at)
                    VALUES (7,9,'new',REPEAT('b',64),'new',FALSE,13,14,
                            'deepseek','deepseek-v4-flash','RUNNING',
                            CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            try (var current = connection.createStatement().executeQuery(
                    "SELECT model_provider_snapshot,model_snapshot "
                            + "FROM agent_v2_turn_intakes "
                            + "WHERE client_request_id='new'")) {
                current.next();
                assertEquals("deepseek", current.getString(1));
                assertEquals("deepseek-v4-flash", current.getString(2));
            }
        }
    }

    @Test
    void productionMigrationDoesNotRewriteHistoricalTurns()
            throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V65__add_v2_turn_model_snapshot.sql"));
        assertTrue(sql.contains("model_provider_snapshot VARCHAR(64) NULL"));
        assertTrue(sql.contains("model_snapshot VARCHAR(128) NULL"));
        assertTrue(!sql.toUpperCase().contains("UPDATE "));
    }

    private static void execute(
            java.sql.Connection connection, String sql) throws Exception {
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                connection.createStatement().execute(statement);
            }
        }
    }
}
