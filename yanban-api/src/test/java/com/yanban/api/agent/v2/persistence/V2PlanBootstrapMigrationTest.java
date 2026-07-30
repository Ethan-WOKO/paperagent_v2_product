package com.yanban.api.agent.v2.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2PlanBootstrapMigrationTest {
    @Test
    void migrationCreatesExactIndependentTableContract() throws Exception {
        String url = "jdbc:h2:mem:v2bootstrap_migration;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE preexisting_marker (id INT PRIMARY KEY)");
        }
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("41")
                .target("42")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Set<String> columns = columnNames(connection, "AGENT_V2_PLAN_BOOTSTRAPS");
            assertEquals(Set.of(
                    "PLAN_ID",
                    "TASK_FRAME_ID",
                    "PAYLOAD_FORMAT_VERSION",
                    "PAYLOAD_SHA256",
                    "PAYLOAD_JSON",
                    "CREATED_AT"), columns);
            assertEquals(Set.of("PLAN_ID"), primaryKeys(connection));
            assertTrue(hasUniqueIndex(connection, "TASK_FRAME_ID"));
            assertFalse(tableExists(connection, "AGENT_PLANS"));
            assertFalse(tableExists(connection, "AGENT_PLAN_STEPS"));
            assertRequired(connection, "AGENT_V2_PLAN_BOOTSTRAPS", columns);
        }
    }

    private static Set<String> columnNames(Connection connection, String table)
            throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, table, null)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static Set<String> primaryKeys(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(
                null, null, "AGENT_V2_PLAN_BOOTSTRAPS")) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static boolean hasUniqueIndex(Connection connection, String column)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, "AGENT_V2_PLAN_BOOTSTRAPS", true, false)) {
            while (rows.next()) {
                if (column.equals(rows.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tableExists(Connection connection, String table)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(
                null, null, table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }

    private static void assertRequired(
            Connection connection,
            String table,
            Set<String> expected) throws Exception {
        Set<String> required = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, table, null)) {
            while (rows.next()) {
                if ("NO".equals(rows.getString("IS_NULLABLE"))) {
                    required.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        assertEquals(expected, required);
    }
}
