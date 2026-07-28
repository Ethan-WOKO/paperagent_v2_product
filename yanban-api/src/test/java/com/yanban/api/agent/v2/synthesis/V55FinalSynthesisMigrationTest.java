package com.yanban.api.agent.v2.synthesis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

class V55FinalSynthesisMigrationTest {
    @Test
    void h2MigrationCreatesSynthesisAndDeliveryAuthorities() throws Exception {
        String url = "jdbc:h2:mem:v55_synthesis;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             Reader script = Files.newBufferedReader(Path.of(
                     "src/test/resources/db/migration-h2/"
                             + "V55__create_agent_v2_final_syntheses.sql"))) {
            RunScript.execute(connection, script);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into agent_v2_final_syntheses
                        (plan_id,synthesis_id,task_frame_id,plan_revision_id,
                         receipt_ids_json,narrative,observed_at,
                         canonical_sha256,committed_at)
                        values ('plan','synthesis','task','revision','[]',
                                'queued',current_timestamp,
                                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                                current_timestamp)
                        """);
                statement.executeUpdate("""
                        insert into agent_v2_literature_deliveries
                        (user_id,session_id,client_request_id,request_sha256,
                         user_message_id,turn_id,lease_owner_id,lease_token,
                         lease_expires_at,plan_id,synthesis_id,
                         assistant_message_id,status,created_at,updated_at)
                        values (7,9,'request',
                                'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                                11,12,'owner','token',current_timestamp,
                                'plan','synthesis',13,'DELIVERED',
                                current_timestamp,current_timestamp)
                        """);
                try (var result = statement.executeQuery(
                        "select count(*) from agent_v2_literature_deliveries")) {
                    result.next();
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }
}
