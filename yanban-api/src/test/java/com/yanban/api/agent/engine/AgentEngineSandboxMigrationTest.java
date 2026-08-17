package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class AgentEngineSandboxMigrationTest {
    @Test
    void v87CreatesOnlyTheEngineSandboxRecoveryAuthority() throws Exception {
        String url = "jdbc:h2:mem:agent_engine_v87;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE preexisting_marker (id INT PRIMARY KEY)");
        }
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("86")
                .target("87")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(columns(connection)).containsExactlyInAnyOrder(
                    "ID", "TASK_ID", "CLIENT_REQUEST_ID", "REQUEST_DIGEST",
                    "SEMANTIC_DIGEST", "EXECUTION_REF", "BROKER_EXECUTION_REF",
                    "STATE", "REQUEST_JSON", "RECEIPT_REF", "RECEIPT_JSON",
                    "CREATED_AT", "UPDATED_AT");
            assertThat(uniqueColumns(connection)).contains(
                    "TASK_ID", "CLIENT_REQUEST_ID", "EXECUTION_REF", "RECEIPT_REF");
            assertThat(tableExists(connection, "SANDBOX_EXECUTION_OUTBOX")).isFalse();
            assertThat(tableExists(connection, "AGENT_V2_RECEIPTS")).isFalse();
        }
    }

    private static Set<String> columns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, "AGENT_ENGINE_SANDBOX_EXECUTIONS", null)) {
            while (rows.next()) result.add(rows.getString("COLUMN_NAME"));
        }
        return result;
    }

    private static Set<String> uniqueColumns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, "AGENT_ENGINE_SANDBOX_EXECUTIONS", true, false)) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME");
                if (column != null) result.add(column);
            }
        }
        return result;
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(
                null, null, table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }
}
