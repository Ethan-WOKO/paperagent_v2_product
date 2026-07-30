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

class V2StepActivationMigrationTest {
    private static final String TABLE = "AGENT_V2_STEP_ACTIVATIONS";

    @Test
    void v46CreatesExtensibleImmutableActivationRowsOnly() throws Exception {
        String url = "jdbc:h2:mem:v2step_activation_migration;"
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
                .baselineOnMigrate(true).baselineVersion("45")
                .target("46")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertEquals(Set.of("ACTIVATION_EVENT_ID"), primaryKey(connection));
            assertTrue(hasIndex(connection, "PLAN_ID", false));
            assertEquals(20, columns(connection).size());
            assertEquals(6, timestampScale(connection, "COMMITTED_AT"));
            assertCheckBehavior(connection);
            assertFalse(tableExists(connection, "AGENT_V2_EVENTS"));
            assertFalse(tableExists(connection, "AGENT_V2_CHECKPOINTS"));
            assertFalse(tableExists(connection, "AGENT_PLANS"));
        }
    }

    private static void assertCheckBehavior(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps VALUES (
                      'plan-a', 'task-a', 1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}', TIMESTAMP '2026-07-27 00:00:00')
                    """);
            String valid = """
                    INSERT INTO agent_v2_step_activations VALUES (
                      'plan-a','step-a','event-a','revision-1',1,
                      'revision-1',1,2,3,1,2,'owner-a',1,1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}',1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}',TIMESTAMP '2026-07-27 00:00:00.123456')
                    """;
            statement.execute(valid);
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'event-a'", "'event-b'")
                            .replace(",2,3,1,2,", ",2,4,1,2,")));
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'event-a'", "'event-c'")
                            .replace("'owner-a',1", "' ',1")));
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
            Connection connection, String column, boolean unique)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, TABLE, unique, false)) {
            while (rows.next()) {
                if (column.equals(rows.getString("COLUMN_NAME"))
                        && rows.getBoolean("NON_UNIQUE") != unique) {
                    return true;
                }
            }
        }
        return false;
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
