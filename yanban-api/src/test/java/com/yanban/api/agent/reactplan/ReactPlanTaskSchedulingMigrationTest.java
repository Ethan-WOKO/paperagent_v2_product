package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ReactPlanTaskSchedulingMigrationTest {
    @Test
    void v94AddsClusterLeaseAndCancellationState() throws Exception {
        String url = "jdbc:h2:mem:reactplan_v94;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE reactplan_task_checkpoints ("
                    + "task_id VARCHAR(69) PRIMARY KEY, state VARCHAR(32) NOT NULL, "
                    + "user_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL)");
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("93").target("94")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            assertThat(columns(connection, "REACTPLAN_TASK_CHECKPOINTS")).contains(
                    "LEASE_OWNER", "LEASE_TOKEN", "LEASE_FENCE", "LEASE_EXPIRES_AT",
                    "CANCELLATION_REQUESTED");
            try (ResultSet row = statement.executeQuery(
                    "SELECT COUNT(*) FROM reactplan_agent_scheduler_lock WHERE lock_id=1")) {
                row.next();
                assertThat(row.getInt(1)).isEqualTo(1);
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
