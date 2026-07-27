package com.yanban.api.agent.v2.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V2StepCompletionMigrationTest {
    @Test
    void v51BindsCompletionToActivationAndOrderedOutcomeEvidence()
            throws Exception {
        String url = "jdbc:h2:mem:v2step_completion_migration;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1";
        createBootstrap(url);
        migrate(url, "50");
        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps VALUES (
                      'plan-a','task-a',1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}',TIMESTAMP '2026-07-28 00:00:00')
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps VALUES (
                      'plan-b','task-b',1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}',TIMESTAMP '2026-07-28 00:00:00')
                    """);
            statement.execute(activation("activation-a", "plan-a", "step-a"));
            statement.execute(activation("activation-b", "plan-b", "step-b"));
            statement.execute("""
                    INSERT INTO agent_v2_tool_call_claims
                    VALUES ('tool-a','EFFECT_INTENT')
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_tool_call_claims
                    VALUES ('tool-b','EFFECT_INTENT')
                    """);
            statement.execute(intent(
                    "tool-a", "plan-a", "step-a", "activation-a"));
            statement.execute(intent(
                    "tool-b", "plan-b", "step-b", "activation-b"));
            statement.execute(receipt("receipt-a", "tool-a"));
            statement.execute(receipt("receipt-b", "tool-b"));
            statement.execute(result("tool-a", "receipt-a"));
            statement.execute(result("tool-b", "receipt-b"));
        }
        migrate(url, "51");

        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(Set.of("COMPLETION_EVENT_ID"),
                    primaryKey(connection, "AGENT_V2_STEP_COMPLETIONS"));
            assertEquals(Set.of("COMPLETION_EVENT_ID", "ORDINAL"),
                    primaryKey(connection,
                            "AGENT_V2_STEP_COMPLETION_EVIDENCE"));
            statement.execute(completion(
                    "completion-a", "plan-a", "step-a", "activation-a"));
            statement.execute("""
                    INSERT INTO agent_v2_step_completion_evidence
                    VALUES (
                      'completion-a',0,'plan-a','step-a','activation-a',
                      'tool-a','receipt-a')
                    """);
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO agent_v2_step_completion_evidence
                    VALUES (
                      'completion-a',1,'plan-a','step-a','activation-a',
                      'tool-b','receipt-b')
                    """));
            assertThrows(SQLException.class, () -> statement.execute(
                    completion("completion-b", "plan-a", "step-a",
                            "activation-a")));
            assertThrows(SQLException.class, () -> statement.execute(
                    completion("completion-other", "plan-other", "step-a",
                            "activation-a")));
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO agent_v2_step_completion_evidence
                    VALUES (
                      'completion-a',1,'plan-a','step-a','activation-a',
                      'tool-a','wrong-receipt')
                    """));
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO agent_v2_step_completion_evidence
                    VALUES (
                      'missing',0,'plan-a','step-a','activation-a',
                      'tool-a','receipt-a')
                    """));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM agent_v2_effect_results "
                            + "WHERE tool_call_id='tool-a'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM agent_v2_step_activations "
                            + "WHERE activation_event_id='activation-a'"));
        }
    }

    private static void createBootstrap(String url) throws Exception {
        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_v2_plan_bootstraps (
                      plan_id VARCHAR(128) PRIMARY KEY,
                      task_frame_id VARCHAR(128) NOT NULL,
                      payload_format_version INT NOT NULL,
                      payload_sha256 CHAR(64) NOT NULL,
                      payload_json LONGTEXT NOT NULL,
                      created_at TIMESTAMP(6) NOT NULL)
                    """);
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("45")
                .target(target).load().migrate();
    }

    private static String activation(
            String event, String plan, String step) {
        return """
                INSERT INTO agent_v2_step_activations VALUES (
                  '%s','%s','%s','revision-a',1,'revision-a',1,
                  2,3,1,2,'owner-a',1,1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(plan, step, event);
    }

    private static String intent(
            String tool, String plan, String step, String activation) {
        return """
                INSERT INTO agent_v2_effect_intents VALUES (
                  '%s','%s','%s','%s','search',
                  'owner-a',1,1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456',
                  'EFFECT_INTENT')
                """.formatted(tool, plan, step, activation);
    }

    private static String receipt(String receipt, String tool) {
        return """
                INSERT INTO agent_v2_receipts VALUES (
                  '%s','%s','EFFECT_INTENT','EFFECT_OUTCOME',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(receipt, tool);
    }

    private static String result(String tool, String receipt) {
        return """
                INSERT INTO agent_v2_effect_results VALUES (
                  '%s','%s','owner-a',1,1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(tool, receipt);
    }

    private static String completion(
            String event, String plan, String step, String activation) {
        return """
                INSERT INTO agent_v2_step_completions VALUES (
                  '%s','%s','%s','%s','revision-a',1,'revision-b',2,
                  3,4,2,3,'owner-a',1,1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(event, plan, step, activation);
    }

    private static Set<String> primaryKey(
            Connection connection, String table) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(
                null, null, table)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }
}
