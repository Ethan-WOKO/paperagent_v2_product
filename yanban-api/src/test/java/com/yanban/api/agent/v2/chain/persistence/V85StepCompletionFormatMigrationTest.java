package com.yanban.api.agent.v2.chain.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;

class V85StepCompletionFormatMigrationTest {
    @Test
    void retainsLegacyFormatAndAcceptsOnlyCompleteCurrentFormatPairs()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v85-step-completion-format");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_v2_step_completions (
                      completion_event_id VARCHAR(128) PRIMARY KEY,
                      request_format_version INT NOT NULL,
                      result_format_version INT NOT NULL,
                      CONSTRAINT ck_agent_v2_step_completion_formats CHECK (
                        request_format_version = 1
                        AND result_format_version = 1))
                    """);
            ChainMigrationTestSupport.execute(connection,
                    ChainMigrationTestSupport.read(true,
                            ChainMigrationTestSupport.fileName(85)));

            insert(statement, "legacy", 1, 1);
            insert(statement, "current", 2, 2);
            assertThrows(SQLException.class,
                    () -> insert(statement, "mixed", 1, 2));
            assertThrows(SQLException.class,
                    () -> insert(statement, "unknown", 3, 3));
        }
    }

    private static void insert(
            Statement statement, String id, int request, int result)
            throws SQLException {
        statement.execute("""
                INSERT INTO agent_v2_step_completions(
                  completion_event_id,request_format_version,
                  result_format_version)
                VALUES ('%s',%d,%d)
                """.formatted(id, request, result));
    }
}
