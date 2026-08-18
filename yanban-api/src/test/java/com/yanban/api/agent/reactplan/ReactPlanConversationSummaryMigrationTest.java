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

class ReactPlanConversationSummaryMigrationTest {
    @Test
    void v95CreatesDurablePerSessionSummaryWorkState() throws Exception {
        String url = "jdbc:h2:mem:reactplan_v95;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sys_users (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE agent_sessions (id BIGINT PRIMARY KEY)");
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("94").target("95")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(columns(connection, "REACTPLAN_CONVERSATION_SUMMARIES")).contains(
                    "SESSION_ID", "USER_ID", "SUMMARY_TEXT", "COVERED_INTAKE_ID",
                    "TARGET_INTAKE_ID", "COVERED_TURN_COUNT", "STATE",
                    "ATTEMPT_COUNT", "LEASE_EXPIRES_AT", "LAST_ERROR");
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
