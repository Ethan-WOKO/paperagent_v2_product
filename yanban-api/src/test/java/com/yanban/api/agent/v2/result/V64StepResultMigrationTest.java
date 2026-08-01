package com.yanban.api.agent.v2.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V64StepResultMigrationTest {
    @Test
    void h2MigrationCreatesConstrainedStepResultStore()
            throws Exception {
        String url = "jdbc:h2:mem:v64-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            execute(connection, Files.readString(Path.of(
                    "src/test/resources/db/migration-h2/"
                            + "V64__create_agent_v2_step_results.sql")));
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_v2_step_results(
                      result_id,plan_id,plan_revision_id,step_id,
                      activation_event_id,source,proposed_text,
                      proposed_sha256,evidence_receipt_ids_json,status,
                      created_at,updated_at)
                    VALUES ('result-1','plan-1','revision-1','step-1',
                      'activation-1','MODEL','answer',REPEAT('a',64),
                      '[]','PROPOSED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM agent_v2_step_results")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
            assertThrows(Exception.class, () ->
                    connection.createStatement().executeUpdate("""
                        INSERT INTO agent_v2_step_results(
                          result_id,plan_id,plan_revision_id,step_id,
                          activation_event_id,source,proposed_text,
                          proposed_sha256,evidence_receipt_ids_json,status,
                          created_at,updated_at)
                        VALUES ('result-2','plan-1','revision-1','step-1',
                          'activation-2','INVALID','answer',REPEAT('b',64),
                          '[]','PROPOSED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """));
        }
    }

    @Test
    void productionMigrationStoresResultsWithoutDuplicatingReceipts() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V64__create_agent_v2_step_results.sql"));
        assertTrue(sql.contains("CREATE TABLE agent_v2_step_results"));
        assertTrue(sql.contains("evidence_receipt_ids_json"));
        assertTrue(sql.contains("accepted_sha256"));
        assertTrue(sql.contains(
                "uk_agent_v2_step_result_accepted_activation"));
        assertTrue(!sql.contains("CREATE TABLE agent_v2_receipts"));
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
