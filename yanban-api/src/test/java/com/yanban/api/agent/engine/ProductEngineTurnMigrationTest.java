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

class ProductEngineTurnMigrationTest {
    @Test
    void v89CreatesDurableProductToEngineMappingWithoutCredentialColumns() throws Exception {
        String url = "jdbc:h2:mem:agent_engine_v89;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE preexisting_marker (id INT PRIMARY KEY)");
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("88").target("89")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Set<String> columns = columns(connection);
            assertThat(columns).contains("ENGINE_MODE", "USER_ID", "SESSION_ID", "PROJECT_ID",
                    "PROJECT_VERSION", "ROOT_CLIENT_REQUEST_ID", "ENGINE_TASK_ID", "REQUEST_DIGEST",
                    "PRODUCT_REQUEST_DIGEST", "AUTHORITY_JSON", "LAST_SEQUENCE", "PENDING_QUESTION_ID", "FINAL_TEXT");
            assertThat(columns).doesNotContain("TASK_GRANT", "SERVICE_TOKEN", "BROKER_TOKEN", "MODEL_API_KEY");
            assertThat(uniqueColumns(connection)).contains(
                    "USER_ID", "SESSION_ID", "ROOT_CLIENT_REQUEST_ID", "ENGINE_TASK_ID");
        }
    }

    private static Set<String> columns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, null, "AGENT_ENGINE_PRODUCT_TURNS", null)) {
            while (rows.next()) result.add(rows.getString("COLUMN_NAME"));
        }
        return result;
    }

    private static Set<String> uniqueColumns(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, null, "AGENT_ENGINE_PRODUCT_TURNS", true, false)) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME");
                if (column != null) result.add(column);
            }
        }
        return result;
    }
}
