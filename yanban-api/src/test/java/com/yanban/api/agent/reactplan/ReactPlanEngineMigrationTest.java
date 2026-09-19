package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ReactPlanEngineMigrationTest {
    @Test
    void additiveMigrationPreservesOldTasksAndDefaultsTheirEngineToTs() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:engine_migration;MODE=MySQL")) {
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE reactplan_turn_intakes (task_id VARCHAR(69) PRIMARY KEY)");
                sql.execute("INSERT INTO reactplan_turn_intakes VALUES ('existing-task')");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V108__freeze_project_task_engine.sql"));
            try (var sql = connection.createStatement();
                 var rows = sql.executeQuery("SELECT engine FROM reactplan_turn_intakes WHERE task_id='existing-task'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("TS");
            }
            try (var main = new ClassPathResource("db/migration/V108__freeze_project_task_engine.sql").getInputStream();
                 var test = new ClassPathResource("db/migration-h2/V108__freeze_project_task_engine.sql").getInputStream()) {
                assertThat(main.readAllBytes()).isEqualTo(test.readAllBytes());
            }
        }
    }
}
