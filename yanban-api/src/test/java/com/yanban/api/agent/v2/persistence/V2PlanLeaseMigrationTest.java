package com.yanban.api.agent.v2.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2PlanLeaseMigrationTest {
    @Test
    void migrationCreatesExactAppendPreservingLeaseContract() throws Exception {
        String url = "jdbc:h2:mem:v2lease_migration;MODE=MySQL;DB_CLOSE_DELAY=-1";
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
                .baselineVersion("42")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Set<String> expected = Set.of(
                    "PLAN_ID",
                    "FENCING_TOKEN",
                    "OWNER_ID",
                    "LEASE_TOKEN",
                    "ACQUIRED_AT",
                    "EXPIRES_AT",
                    "RELEASED_AT");
            assertEquals(expected, columns(connection));
            assertEquals(List.of("PLAN_ID", "FENCING_TOKEN"), primaryKey(connection));
            assertTrue(hasUniqueIndex(connection, "LEASE_TOKEN"));
            assertEquals(
                    Map.of("PLAN_ID", "AGENT_V2_PLAN_BOOTSTRAPS"),
                    foreignKeys(connection));
            assertRequired(connection, expected, Set.of("RELEASED_AT"));
            assertEquals(Set.of(
                            "CK_AGENT_V2_PLAN_LEASES_FENCE",
                            "CK_AGENT_V2_PLAN_LEASES_OWNER",
                            "CK_AGENT_V2_PLAN_LEASES_TOKEN",
                            "CK_AGENT_V2_PLAN_LEASES_EXPIRY"),
                    checkNames(connection));
            assertCheckBehavior(connection);
            assertTrue(tableExists(connection, "PREEXISTING_MARKER"));
            assertFalse(tableExists(connection, "AGENT_PLANS"));
            assertFalse(tableExists(connection, "AGENT_PLAN_STEPS"));
        }
    }

    private static Set<String> columns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, "AGENT_V2_PLAN_LEASES", null)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static List<String> primaryKey(Connection connection) throws Exception {
        Map<Short, String> ordered = new HashMap<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(
                null, null, "AGENT_V2_PLAN_LEASES")) {
            while (rows.next()) {
                ordered.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
            }
        }
        List<String> result = new ArrayList<>();
        ordered.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add(entry.getValue()));
        return result;
    }

    private static boolean hasUniqueIndex(Connection connection, String column)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, "AGENT_V2_PLAN_LEASES", true, false)) {
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
                null, null, "AGENT_V2_PLAN_LEASES")) {
            while (rows.next()) {
                result.put(
                        rows.getString("FKCOLUMN_NAME"),
                        rows.getString("PKTABLE_NAME"));
            }
        }
        return result;
    }

    private static void assertRequired(
            Connection connection,
            Set<String> expected,
            Set<String> nullable) throws Exception {
        Set<String> required = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, "AGENT_V2_PLAN_LEASES", null)) {
            while (rows.next()) {
                if ("NO".equals(rows.getString("IS_NULLABLE"))) {
                    required.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        Set<String> expectedRequired = new HashSet<>(expected);
        expectedRequired.removeAll(nullable);
        assertEquals(expectedRequired, required);
    }

    private static Set<String> checkNames(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT CONSTRAINT_NAME
                     FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
                     WHERE CONSTRAINT_NAME LIKE 'CK_AGENT_V2_PLAN_LEASES_%'
                     """)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private static void assertCheckBehavior(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps
                    VALUES ('plan-check', 'task-check', 1,
                            '0000000000000000000000000000000000000000000000000000000000000000',
                            '{}', TIMESTAMP '2026-07-27 00:00:00')
                    """);
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO agent_v2_plan_leases
                    VALUES ('plan-check', 0, 'owner', 'token-zero',
                            TIMESTAMP '2026-07-27 00:00:00',
                            TIMESTAMP '2026-07-27 00:01:00', NULL)
                    """));
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO agent_v2_plan_leases
                    VALUES ('plan-check', 1, ' ', 'token-owner',
                            TIMESTAMP '2026-07-27 00:00:00',
                            TIMESTAMP '2026-07-27 00:01:00', NULL)
                    """));
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO agent_v2_plan_leases
                    VALUES ('plan-check', 1, 'owner', ' ',
                            TIMESTAMP '2026-07-27 00:00:00',
                            TIMESTAMP '2026-07-27 00:01:00', NULL)
                    """));
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO agent_v2_plan_leases
                    VALUES ('plan-check', 1, 'owner', 'token-expiry',
                            TIMESTAMP '2026-07-27 00:00:00',
                            TIMESTAMP '2026-07-27 00:00:00', NULL)
                    """));
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
