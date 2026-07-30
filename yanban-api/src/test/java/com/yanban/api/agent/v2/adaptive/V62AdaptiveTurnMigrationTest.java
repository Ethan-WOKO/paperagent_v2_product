package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V62AdaptiveTurnMigrationTest {
    @Test
    void migrationFreezesStableStatusesAndRequestAuthority() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V62__create_agent_v2_adaptive_turns.sql"));
        assertTrue(sql.contains(
                "UNIQUE KEY uk_agent_v2_adaptive_request"));
        assertTrue(sql.contains(
                "'PLANNING','RUNNING','WAITING_CONFIRMATION','SUCCEEDED','FAILED'"));
        assertTrue(sql.contains(
                "REFERENCES agent_v2_turn_intakes(id)"));
    }

    @Test
    void h2MigrationEnforcesStatusAndRequestUniqueness() throws Exception {
        String url = "jdbc:h2:mem:v62-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute(
                    "CREATE TABLE agent_v2_turn_intakes("
                            + "id BIGINT PRIMARY KEY)");
            connection.createStatement().execute(
                    "INSERT INTO agent_v2_turn_intakes(id) VALUES (1),(2)");
            String sql = Files.readString(Path.of(
                    "src/test/resources/db/migration-h2/"
                            + "V62__create_agent_v2_adaptive_turns.sql"));
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) {
                    connection.createStatement().execute(statement);
                }
            }
            insert(connection, "request-1", "RUNNING");
            assertThrows(SQLException.class,
                    () -> insert(connection, "request-1", "SUCCEEDED"));
            assertThrows(SQLException.class,
                    () -> insert(connection, "request-2", "UNKNOWN"));
        }
    }

    private static void insert(
            java.sql.Connection connection, String request, String status)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO agent_v2_adaptive_turns(
                  intake_id,user_id,session_id,client_request_id,route,
                  status,steps_json,output_paths_json,reflection_count,
                  replan_count,repair_count,created_at,updated_at)
                VALUES (?,7,8,?,'PERSISTENT_PLAN_EXECUTE',?,'[]','[]',
                        0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """)) {
            statement.setLong(1, request.endsWith("1") ? 1 : 2);
            statement.setString(2, request);
            statement.setString(3, status);
            statement.executeUpdate();
        }
    }
}
