package com.yanban.api.agent.v2.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2ExecutionStartMigrationTest {
    private static final String TABLE = "AGENT_V2_EXECUTION_STARTS";

    @Test
    void v44CreatesOnlyTheImmutableExecutionStartAuthority() throws Exception {
        String url = "jdbc:h2:mem:v2execution_start_migration;"
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
                        created_at TIMESTAMP(6) NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE preexisting_marker (id INT PRIMARY KEY)");
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("43")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Set<String> expected = Set.of(
                    "PLAN_ID", "START_EVENT_ID", "LEASE_OWNER_ID",
                    "FENCING_TOKEN", "REQUEST_FORMAT_VERSION",
                    "REQUEST_SHA256", "REQUEST_JSON", "RESULT_FORMAT_VERSION",
                    "RESULT_SHA256", "RESULT_JSON", "COMMITTED_AT");
            assertEquals(expected, columns(connection));
            assertEquals(Set.of("PLAN_ID"), primaryKey(connection));
            assertTrue(hasUniqueIndex(connection, "START_EVENT_ID"));
            assertEquals(
                    Map.of("PLAN_ID", "AGENT_V2_PLAN_BOOTSTRAPS"),
                    foreignKeys(connection));
            assertEquals(expected, required(connection));
            assertEquals(6, timestampScale(connection, "COMMITTED_AT"));
            assertEquals(Set.of(
                            "CK_AGENT_V2_EXECUTION_STARTS_OWNER",
                            "CK_AGENT_V2_EXECUTION_STARTS_FENCE",
                            "CK_AGENT_V2_EXECUTION_STARTS_REQUEST_FORMAT",
                            "CK_AGENT_V2_EXECUTION_STARTS_RESULT_FORMAT",
                            "CK_AGENT_V2_EXECUTION_STARTS_REQUEST_SHA256",
                            "CK_AGENT_V2_EXECUTION_STARTS_RESULT_SHA256"),
                    checkNames(connection));
            assertCheckBehavior(connection);
            assertTrue(tableExists(connection, "PREEXISTING_MARKER"));
            assertFalse(tableExists(connection, "AGENT_V2_EVENTS"));
            assertFalse(tableExists(connection, "AGENT_V2_CHECKPOINTS"));
            assertFalse(tableExists(connection, "AGENT_PLANS"));
        }
    }

    private static Set<String> columns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, TABLE, null)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static Set<String> primaryKey(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(
                null, null, TABLE)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static boolean hasUniqueIndex(Connection connection, String column)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, TABLE, true, false)) {
            while (rows.next()) {
                if (column.equals(rows.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> foreignKeys(Connection connection)
            throws Exception {
        Map<String, String> result = new HashMap<>();
        try (ResultSet rows = connection.getMetaData().getImportedKeys(
                null, null, TABLE)) {
            while (rows.next()) {
                result.put(rows.getString("FKCOLUMN_NAME"),
                        rows.getString("PKTABLE_NAME"));
            }
        }
        return result;
    }

    private static Set<String> required(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, TABLE, null)) {
            while (rows.next()) {
                if ("NO".equals(rows.getString("IS_NULLABLE"))) {
                    result.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        return result;
    }

    private static Set<String> checkNames(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT CONSTRAINT_NAME
                     FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
                     WHERE CONSTRAINT_NAME LIKE
                         'CK_AGENT_V2_EXECUTION_STARTS_%'
                     """)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private static void assertCheckBehavior(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps
                    VALUES ('plan-a', 'task-a', 1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}', TIMESTAMP '2026-07-27 00:00:00')
                    """);
            String valid = """
                    INSERT INTO agent_v2_execution_starts VALUES (
                      'plan-a', 'event-a', 'owner-a', 1, 1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}', 1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}', TIMESTAMP '2026-07-27 00:00:00.123456')
                    """;
            statement.execute(valid);
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'plan-a', 'event-a', 'owner-a', 1",
                            "'plan-a', 'event-b', 'owner-a', 0")));
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps
                    VALUES ('plan-b', 'task-b', 1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}', TIMESTAMP '2026-07-27 00:00:00')
                    """);
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'plan-a', 'event-a', 'owner-a', 1",
                            "'plan-b', 'event-b', ' ', 1")));
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace(
                            "'plan-a', 'event-a', 'owner-a', 1, 1",
                            "'plan-b', 'event-b', 'owner-b', 1, 2")));
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
