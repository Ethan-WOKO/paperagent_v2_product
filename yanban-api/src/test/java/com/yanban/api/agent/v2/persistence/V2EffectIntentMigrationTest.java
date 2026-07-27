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

class V2EffectIntentMigrationTest {
    private static final String TABLE = "AGENT_V2_EFFECT_INTENTS";

    @Test
    void v48CreatesOnlyImmutableBoundedEffectIntentMarkers()
            throws Exception {
        String url = "jdbc:h2:mem:v2effect_intent_migration;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
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
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("47")
                .load().migrate();

        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "")) {
            assertEquals(Set.of("TOOL_CALL_ID"), primaryKey(connection));
            assertTrue(hasIndex(connection, Set.of("PLAN_ID", "STEP_ID")));
            assertEquals(14, columns(connection).size());
            assertEquals(128, columnSize(connection, "TOOL_CALL_ID"));
            assertEquals(128, columnSize(connection, "PLAN_ID"));
            assertEquals(128, columnSize(connection, "STEP_ID"));
            assertEquals(128, columnSize(connection, "ACTIVATION_EVENT_ID"));
            assertEquals(128, columnSize(connection, "INTENT_KIND"));
            assertEquals(6, timestampScale(connection, "COMMITTED_AT"));
            assertCheckBehavior(connection);
            assertFalse(tableExists(connection, "AGENT_V2_EFFECT_OUTCOMES"));
            assertFalse(tableExists(connection, "AGENT_V2_EFFECT_RECEIPTS"));
            assertFalse(tableExists(connection, "AGENT_V2_EVENTS"));
            assertFalse(tableExists(connection, "AGENT_V2_CHECKPOINTS"));
        }
    }

    private static void assertCheckBehavior(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps VALUES (
                      'plan-a', 'task-a', 1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}', TIMESTAMP '2026-07-28 00:00:00')
                    """);
            String valid = """
                    INSERT INTO agent_v2_effect_intents VALUES (
                      'tool-a','plan-a','step-a','activation-a','search',
                      'owner-a',1,1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}',1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}',TIMESTAMP '2026-07-28 00:00:00.123456')
                    """;
            statement.execute(valid);
            assertThrows(SQLException.class, () -> statement.execute(valid));
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'tool-a'", "'tool-b'")
                            .replace("'owner-a',1", "' ',1")));
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'tool-a'", "'tool-c'")
                            .replace("'owner-a',1", "'owner-a',0")));
        }
    }

    private static Set<String> columns(Connection connection)
            throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, TABLE, null)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static Set<String> primaryKey(Connection connection)
            throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(
                null, null, TABLE)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static boolean hasIndex(
            Connection connection, Set<String> columns) throws Exception {
        Set<String> found = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, TABLE, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if ("IDX_AGENT_V2_EFFECT_INTENTS_PLAN_STEP".equals(name)) {
                    found.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        return found.equals(columns);
    }

    private static int columnSize(Connection connection, String column)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, TABLE, column)) {
            assertTrue(rows.next());
            return rows.getInt("COLUMN_SIZE");
        }
    }

    private static int timestampScale(Connection connection, String column)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, TABLE, column)) {
            assertTrue(rows.next());
            return rows.getInt("DECIMAL_DIGITS");
        }
    }

    private static boolean tableExists(Connection connection, String table)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(
                null, null, table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }
}
