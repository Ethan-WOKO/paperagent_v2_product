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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2ReceiptMigrationTest {
    @Test
    void v49BackfillsClaimsAndEnforcesBothDiscriminators()
            throws Exception {
        String url = "jdbc:h2:mem:v2receipt_migration;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1";
        createBootstrap(url);
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("47")
                .target("48")
                .load().migrate();
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
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("47")
                .target("49")
                .load().migrate();

        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(Set.of("TOOL_CALL_ID"),
                    primaryKey(connection, "AGENT_V2_TOOL_CALL_CLAIMS"));
            assertEquals(Set.of("RECEIPT_ID"),
                    primaryKey(connection, "AGENT_V2_RECEIPTS"));
            assertEquals(8, columns(
                    connection, "AGENT_V2_RECEIPTS").size());
            assertTrue(columns(connection, "AGENT_V2_EFFECT_INTENTS")
                    .contains("TOOL_CALL_OWNER_KIND"));
            assertEquals("EFFECT_INTENT", scalar(statement, """
                    SELECT owner_kind
                      FROM agent_v2_tool_call_claims
                     WHERE tool_call_id = 'tool-effect'
                    """));
            assertEquals("EFFECT_INTENT", scalar(statement, """
                    SELECT tool_call_owner_kind
                      FROM agent_v2_effect_intents
                     WHERE tool_call_id = 'tool-effect'
                    """));

            statement.execute("""
                    INSERT INTO agent_v2_tool_call_claims
                      (tool_call_id, owner_kind)
                    VALUES ('tool-ordinary', 'ORDINARY_RECEIPT')
                    """);
            statement.execute(receipt(
                    "receipt-a", "tool-ordinary",
                    "ORDINARY_RECEIPT", "ORDINARY_RECEIPT"));
            statement.execute(receipt(
                    "receipt-effect", "tool-effect",
                    "EFFECT_INTENT", "EFFECT_OUTCOME"));
            assertThrows(SQLException.class, () -> statement.execute(
                    receipt("receipt-bad", "tool-ordinary",
                            "ORDINARY_RECEIPT", "EFFECT_OUTCOME")));
            assertThrows(SQLException.class, () -> statement.execute(
                    receipt("receipt-missing", "missing",
                            "ORDINARY_RECEIPT", "ORDINARY_RECEIPT")));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM agent_v2_tool_call_claims "
                            + "WHERE tool_call_id = 'tool-effect'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO agent_v2_tool_call_claims VALUES "
                            + "('bad', 'UNKNOWN')"));
            assertFalse(tableExists(
                    connection, "AGENT_V2_EFFECT_OUTCOMES"));
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

    private static Object scalar(Statement statement, String sql)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getObject(1);
        }
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

    private static boolean tableExists(
            Connection connection, String table) throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(
                null, null, table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }
}
