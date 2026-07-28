package com.yanban.api.agent.v2.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V2StepLifecycleMigrationTest {
    private static final String ZERO =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    void v52PreservesFirstStepRowsAndAllowsOneLifecyclePerStep()
            throws Exception {
        String url = "jdbc:h2:mem:v2_step_lifecycle_migration;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1";
        createBootstrap(url);
        migrate(url, "51");
        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_plan_bootstraps VALUES (
                      'plan-a','task-a',1,'%s','{}',
                      TIMESTAMP '2026-07-28 00:00:00')
                    """.formatted(ZERO));
            statement.execute(activation(
                    "activation-a", "step-a", "revision-1", 1,
                    2, 3, 1, 2));
            statement.execute(completion(
                    "completion-a", "step-a", "activation-a",
                    "revision-1", 1, "revision-2", 2,
                    3, 4, 2, 3));
        }

        migrate(url, "52");

        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(1, count(statement,
                    "agent_v2_step_activations"));
            assertEquals(1, count(statement,
                    "agent_v2_step_completions"));
            assertEquals(ZERO, scalar(statement,
                    "SELECT request_sha256 FROM "
                            + "agent_v2_step_activations "
                            + "WHERE activation_event_id='activation-a'"));
            assertEquals(ZERO, scalar(statement,
                    "SELECT result_sha256 FROM "
                            + "agent_v2_step_completions "
                            + "WHERE completion_event_id='completion-a'"));

            statement.execute(activation(
                    "activation-b", "step-b", "revision-2", 2,
                    4, 5, 3, 4));
            statement.execute(completion(
                    "completion-b", "step-b", "activation-b",
                    "revision-2", 2, "revision-3", 3,
                    5, 6, 4, 5));
            assertEquals(2, count(statement,
                    "agent_v2_step_activations"));
            assertEquals(2, count(statement,
                    "agent_v2_step_completions"));

            assertThrows(SQLException.class, () -> statement.execute(
                    activation("activation-b-duplicate", "step-b",
                            "revision-2", 2, 4, 5, 3, 4)));
            assertThrows(SQLException.class, () -> statement.execute(
                    completion("completion-b-duplicate", "step-b",
                            "activation-b", "revision-2", 2,
                            "revision-3", 3, 5, 6, 4, 5)));
            assertThrows(SQLException.class, () -> statement.execute(
                    activation("activation-c", "step-c",
                            "revision-3", 3, 6, 8, 5, 6)));
        }
    }

    private static void createBootstrap(String url) throws Exception {
        try (Connection connection =
                     DriverManager.getConnection(url, "sa", "");
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
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("45")
                .target(target).load().migrate();
    }

    private static String activation(
            String event, String step, String revision, long revisionNumber,
            long sourceCheckpoint, long resultCheckpoint,
            long sourceEvent, long resultEvent) {
        return """
                INSERT INTO agent_v2_step_activations VALUES (
                  'plan-a','%s','%s','%s',%d,'%s',%d,
                  %d,%d,%d,%d,'owner-a',1,1,'%s','{}',1,'%s','{}',
                  TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(
                step, event, revision, revisionNumber,
                revision, revisionNumber,
                sourceCheckpoint, resultCheckpoint,
                sourceEvent, resultEvent, ZERO, ZERO);
    }

    private static String completion(
            String event, String step, String activation,
            String sourceRevision, long sourceRevisionNumber,
            String resultRevision, long resultRevisionNumber,
            long sourceCheckpoint, long resultCheckpoint,
            long sourceEvent, long resultEvent) {
        return """
                INSERT INTO agent_v2_step_completions VALUES (
                  '%s','plan-a','%s','%s','%s',%d,'%s',%d,
                  %d,%d,%d,%d,'owner-a',1,1,'%s','{}',1,'%s','{}',
                  TIMESTAMP '2026-07-28 00:00:00.123456')
                """.formatted(
                event, step, activation,
                sourceRevision, sourceRevisionNumber,
                resultRevision, resultRevisionNumber,
                sourceCheckpoint, resultCheckpoint,
                sourceEvent, resultEvent, ZERO, ZERO);
    }

    private static int count(Statement statement, String table)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String scalar(Statement statement, String query)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
