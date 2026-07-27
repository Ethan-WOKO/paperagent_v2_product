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

class V2PlanExecutionContextMigrationTest {
    private static final String TABLE =
            "AGENT_V2_PLAN_EXECUTION_CONTEXTS";

    @Test
    void v45CreatesOnlyCanonicalContextAndGlobalWorkspaceAuthority()
            throws Exception {
        String url = "jdbc:h2:mem:v2context_migration;"
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
            statement.execute(
                    "CREATE TABLE preexisting_marker (id INT PRIMARY KEY)");
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("44")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Set<String> expected = Set.of(
                    "PLAN_ID", "WORKSPACE_ID",
                    "RESERVATION_LEASE_OWNER_ID",
                    "RESERVATION_FENCING_TOKEN",
                    "RESERVATION_REQUEST_FORMAT_VERSION",
                    "RESERVATION_REQUEST_SHA256",
                    "RESERVATION_REQUEST_JSON",
                    "RESERVATION_RESULT_FORMAT_VERSION",
                    "RESERVATION_RESULT_SHA256",
                    "RESERVATION_RESULT_JSON",
                    "CONFIRMATION_LEASE_OWNER_ID",
                    "CONFIRMATION_FENCING_TOKEN",
                    "CONFIRMATION_REQUEST_FORMAT_VERSION",
                    "CONFIRMATION_REQUEST_SHA256",
                    "CONFIRMATION_REQUEST_JSON",
                    "CONFIRMATION_RESULT_FORMAT_VERSION",
                    "CONFIRMATION_RESULT_SHA256",
                    "CONFIRMATION_RESULT_JSON",
                    "SOURCE_MANIFEST_FINGERPRINT");
            assertEquals(expected, columns(connection));
            assertEquals(Set.of("PLAN_ID"), primaryKey(connection));
            assertTrue(hasUniqueIndex(connection, "WORKSPACE_ID"));
            assertEquals(Map.of("PLAN_ID", "AGENT_V2_PLAN_BOOTSTRAPS"),
                    foreignKeys(connection));
            assertTrue(required(connection).containsAll(Set.of(
                    "PLAN_ID", "WORKSPACE_ID",
                    "RESERVATION_LEASE_OWNER_ID",
                    "RESERVATION_FENCING_TOKEN",
                    "RESERVATION_REQUEST_FORMAT_VERSION",
                    "RESERVATION_REQUEST_SHA256",
                    "RESERVATION_REQUEST_JSON",
                    "RESERVATION_RESULT_FORMAT_VERSION",
                    "RESERVATION_RESULT_SHA256",
                    "RESERVATION_RESULT_JSON")));
            assertCheckBehavior(connection);
            assertTrue(tableExists(connection, "PREEXISTING_MARKER"));
            assertFalse(tableExists(connection, "AGENT_V2_WORKSPACES"));
            assertFalse(tableExists(connection, "AGENT_V2_STEPS"));
        }
    }

    private static void assertCheckBehavior(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps VALUES
                    ('plan-a','task-a',1,
                    '0000000000000000000000000000000000000000000000000000000000000000',
                    '{}',TIMESTAMP '2026-07-27 00:00:00'),
                    ('plan-b','task-b',1,
                    '0000000000000000000000000000000000000000000000000000000000000000',
                    '{}',TIMESTAMP '2026-07-27 00:00:00')
                    """);
            String valid = """
                    INSERT INTO agent_v2_plan_execution_contexts (
                      plan_id, workspace_id,
                      reservation_lease_owner_id,
                      reservation_fencing_token,
                      reservation_request_format_version,
                      reservation_request_sha256,
                      reservation_request_json,
                      reservation_result_format_version,
                      reservation_result_sha256,
                      reservation_result_json)
                    VALUES ('plan-a','workspace-a','owner-a',1,1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}',1,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      '{}')
                    """;
            statement.execute(valid);
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'plan-a','workspace-a'",
                            "'plan-b','workspace-a'")));
            assertThrows(SQLException.class, () -> statement.execute(
                    valid.replace("'plan-a','workspace-a','owner-a',1",
                            "'plan-b','workspace-b','owner-b',0")));
            assertThrows(SQLException.class, () -> statement.execute("""
                    UPDATE agent_v2_plan_execution_contexts
                    SET confirmation_lease_owner_id='owner',
                        confirmation_fencing_token=2
                    WHERE plan_id='plan-a'
                    """));
        }
    }

    private static Set<String> columns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, TABLE, null)) {
            while (rows.next()) { result.add(rows.getString("COLUMN_NAME")); }
        }
        return result;
    }

    private static Set<String> primaryKey(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(
                null, null, TABLE)) {
            while (rows.next()) { result.add(rows.getString("COLUMN_NAME")); }
        }
        return result;
    }

    private static boolean hasUniqueIndex(
            Connection connection, String column) throws Exception {
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

    private static boolean tableExists(Connection connection, String table)
            throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(
                null, null, table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }
}
