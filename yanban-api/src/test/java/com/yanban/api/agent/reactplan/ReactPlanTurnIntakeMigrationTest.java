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

class ReactPlanTurnIntakeMigrationTest {
    @Test
    void v88CreatesOnlyTheReActTurnIntakeAuthority() throws Exception {
        String url = "jdbc:h2:mem:reactplan_v88;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE preexisting_marker (id INT PRIMARY KEY)");
        }
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("87")
                .target("88")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(columns(connection)).containsExactlyInAnyOrder(
                    "ID", "USER_ID", "SESSION_ID", "CLIENT_REQUEST_ID",
                    "REQUEST_DIGEST", "TURN_ID", "USER_MESSAGE_ID",
                    "TASK_ID", "CREATED_AT");
            assertThat(uniqueColumns(connection)).contains(
                    "USER_ID", "SESSION_ID", "CLIENT_REQUEST_ID",
                    "TURN_ID", "TASK_ID");
            assertThat(tableExists(connection, "AGENT_TURNS")).isFalse();
        }
    }

    private static Set<String> columns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, "REACTPLAN_TURN_INTAKES", null)) {
            while (rows.next()) result.add(rows.getString("COLUMN_NAME"));
        }
        return result;
    }

    private static Set<String> uniqueColumns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, "REACTPLAN_TURN_INTAKES", true, false)) {
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
