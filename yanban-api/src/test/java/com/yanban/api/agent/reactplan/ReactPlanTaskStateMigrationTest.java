package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ReactPlanTaskStateMigrationTest {
    @Test
    void v89CreatesDurableTaskStateAndSessionCascade() throws Exception {
        String url = "jdbc:h2:mem:reactplan_v89;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_sessions (id BIGINT PRIMARY KEY)");
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("88").target("89")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(columns(connection, "REACTPLAN_TASK_CHECKPOINTS")).contains(
                    "TASK_ID", "REQUEST_DIGEST", "USER_ID", "SESSION_ID", "TURN_ID",
                    "STATE", "LAST_SEQUENCE", "CHECKPOINT_REVISION", "CHECKPOINT_JSON");
            assertThat(columns(connection, "REACTPLAN_TASK_EVENTS")).contains(
                    "TASK_ID", "SEQUENCE_NUMBER", "EVENT_JSON", "OCCURRED_AT");

            statement.execute("INSERT INTO agent_sessions(id) VALUES (11)");
            statement.execute("INSERT INTO reactplan_task_checkpoints "
                    + "(task_id,request_digest,user_id,session_id,turn_id,state,last_sequence,"
                    + "checkpoint_revision,checkpoint_json,created_at,updated_at) VALUES "
                    + "('task." + "a".repeat(64) + "','" + "b".repeat(64)
                    + "',7,11,42,'running',1,1,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO reactplan_task_events "
                    + "(task_id,sequence_number,event_json,occurred_at) VALUES "
                    + "('task." + "a".repeat(64) + "',1,'{}',CURRENT_TIMESTAMP)");
            statement.execute("DELETE FROM agent_sessions WHERE id=11");
            assertThat(count(statement, "reactplan_task_checkpoints")).isZero();
            assertThat(count(statement, "reactplan_task_events")).isZero();
        }
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rows.next()) result.add(rows.getString("COLUMN_NAME"));
        }
        return result;
    }

    private static long count(Statement statement, String table) throws Exception {
        try (ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }
}
