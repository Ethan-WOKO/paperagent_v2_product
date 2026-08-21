package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ReactPlanRegisteredToolMigrationTest {
    @Test
    void v96CreatesDurableIdempotentRegisteredToolCalls() throws Exception {
        String url = "jdbc:h2:mem:reactplan_v96;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE reactplan_task_checkpoints ("
                    + "task_id VARCHAR(69) PRIMARY KEY)");
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("95").target("96")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            assertThat(columns(connection, "REACTPLAN_REGISTERED_TOOL_CALLS")).contains(
                    "TASK_ID", "CALL_ID", "TOOL_NAME", "REQUEST_DIGEST", "STATE",
                    "RESPONSE_JSON", "ERROR_CODE", "REPLAY_COUNT");
            String taskId = "task." + "a".repeat(64);
            statement.execute("INSERT INTO reactplan_task_checkpoints(task_id) VALUES ('"
                    + taskId + "')");
            statement.execute("INSERT INTO reactplan_registered_tool_calls "
                    + "(task_id,call_id,tool_name,request_digest,state,created_at,updated_at) VALUES ('"
                    + taskId + "','call." + "b".repeat(40) + "','project_search','"
                    + "c".repeat(64) + "','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.execute("DELETE FROM reactplan_task_checkpoints WHERE task_id='"
                    + taskId + "'");
            try (ResultSet row = statement.executeQuery(
                    "SELECT COUNT(*) FROM reactplan_registered_tool_calls")) {
                row.next();
                assertThat(row.getLong(1)).isZero();
            }
        }
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rows.next()) result.add(rows.getString("COLUMN_NAME"));
        }
        return result;
    }
}
