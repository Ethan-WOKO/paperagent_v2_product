package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.DriverManager;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

class V56LiteratureOutcomeMigrationTest {
    @Test
    void h2UpgradeAddsUniqueWriteOnceBindingSlots() throws Exception {
        upgradeAndAssert(
                "v56_outcomes_h2",
                "src/test/resources/db/migration-h2/"
                        + "V56__bind_v2_literature_task_outcomes.sql");
    }

    @Test
    void productionMigrationUsesStatementsAcceptedInMysqlCompatibleH2()
            throws Exception {
        upgradeAndAssert(
                "v56_outcomes_production",
                "src/main/resources/db/migration/"
                        + "V56__bind_v2_literature_task_outcomes.sql");
    }

    private static void upgradeAndAssert(
            String databaseName, String migrationPath) throws Exception {
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             Reader v55 = Files.newBufferedReader(Path.of(
                     "src/test/resources/db/migration-h2/"
                             + "V55__create_agent_v2_final_syntheses.sql"));
             Reader v56 = Files.newBufferedReader(Path.of(migrationPath))) {
            RunScript.execute(connection, v55);
            RunScript.execute(connection, v56);
            try (var statement = connection.createStatement()) {
                insert(statement, 7, "one", 101, 201);
                insert(statement, 7, "two", 102, 202);
                assertThrows(SQLException.class,
                        () -> insert(statement, 8, "task-conflict", 101, 203));
                assertThrows(SQLException.class,
                        () -> insert(statement, 8, "message-conflict", 103, 201));
                try (var result = statement.executeQuery("""
                        select literature_task_id, result_assistant_message_id
                        from agent_v2_literature_deliveries
                        where user_id=7 and client_request_id='one'
                        """)) {
                    result.next();
                    assertEquals(101L, result.getLong(1));
                    assertEquals(201L, result.getLong(2));
                }
            }
        }
    }

    private static void insert(
            java.sql.Statement statement, long userId, String requestId,
            long taskId, long resultMessageId) throws SQLException {
        statement.executeUpdate("""
                insert into agent_v2_literature_deliveries
                (user_id,session_id,client_request_id,request_sha256,
                 query_text,top_k,year_from,include_bibtex,
                 user_message_id,turn_id,lease_owner_id,lease_token,
                 lease_expires_at,status,created_at,updated_at,
                 literature_task_id,result_assistant_message_id)
                values (%d,9,'%s',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        'query',10,null,false,
                        11,12,'owner','token',current_timestamp,
                        'DELIVERED',current_timestamp,current_timestamp,
                        %d,%d)
                """.formatted(userId, requestId, taskId, resultMessageId));
    }
}
