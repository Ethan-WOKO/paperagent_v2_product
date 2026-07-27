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
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2EffectOutcomeMigrationTest {
    @Test
    void v50AddsImmutableProgressAndAtomicResultReceiptBinding()
            throws Exception {
        String url = "jdbc:h2:mem:v2effect_outcome_migration;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1";
        createBootstrap(url);
        migrate(url, "48");
        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps VALUES (
                      'plan-a', 'task-a', 1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}', TIMESTAMP '2026-07-28 00:00:00')
                    """);
            statement.execute(effectIntent("tool-effect"));
            statement.execute(effectIntent("tool-other"));
        }
        migrate(url, "49");
        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(receipt(
                    "receipt-effect", "tool-effect",
                    "EFFECT_INTENT", "EFFECT_OUTCOME"));
        }
        migrate(url, "50");

        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(Set.of("EFFECT_PROGRESS_ID"),
                    primaryKey(connection, "AGENT_V2_EFFECT_PROGRESS"));
            assertEquals(Set.of("TOOL_CALL_ID"),
                    primaryKey(connection, "AGENT_V2_EFFECT_RESULTS"));
            assertEquals(12, columns(
                    connection, "AGENT_V2_EFFECT_PROGRESS").size());
            assertEquals(11, columns(
                    connection, "AGENT_V2_EFFECT_RESULTS").size());

            statement.execute(progress("progress-a", "tool-effect", 1));
            statement.execute(result(
                    "tool-effect", "receipt-effect"));
            assertThrows(SQLException.class, () -> statement.execute(
                    progress("progress-a", "tool-effect", 2)));
            assertThrows(SQLException.class, () -> statement.execute(
                    progress("progress-b", "tool-effect", 1)));
            assertThrows(SQLException.class, () -> statement.execute(
                    result("missing-tool", "receipt-effect")));
            assertThrows(SQLException.class, () -> statement.execute(
                    result("tool-other", "receipt-effect")));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM agent_v2_receipts "
                            + "WHERE receipt_id = 'receipt-effect'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM agent_v2_effect_intents "
                            + "WHERE tool_call_id = 'tool-effect'"));
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("47")
                .target(target).load().migrate();
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

    private static String effectIntent(String toolCall) {
        return """
                INSERT INTO agent_v2_effect_intents VALUES (
                  '%s','plan-a','step-a','activation-a','search',
                  'owner-a',1,1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(toolCall);
    }

    private static String receipt(
            String receiptId,
            String toolCallId,
            String claimKind,
            String receiptKind) {
        return """
                INSERT INTO agent_v2_receipts VALUES (
                  '%s','%s','%s','%s',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(
                receiptId, toolCallId, claimKind, receiptKind);
    }

    private static String progress(
            String progressId, String toolCallId, long sequence) {
        return """
                INSERT INTO agent_v2_effect_progress VALUES (
                  '%s','%s',%d,'owner-a',1,1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(progressId, toolCallId, sequence);
    }

    private static String result(String toolCallId, String receiptId) {
        return """
                INSERT INTO agent_v2_effect_results VALUES (
                  '%s','%s','owner-a',1,1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(toolCallId, receiptId);
    }

    private static Set<String> columns(
            Connection connection, String table) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, table, null)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
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
