package com.yanban.api.agent.v2.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V66ContextRevisionMigrationTest {
    @Test
    void migratesLegacyRowAndAllowsRevisionTwoWithSections() throws Exception {
        String url = "jdbc:h2:mem:v66-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            createAuthorities(connection);
            execute(connection, sql("V20__create_agent_context_snapshots.sql"));
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_context_snapshots(
                      turn_id,session_id,user_id,trace_id,sections_json,
                      dropped_items_json,raw_message_count,
                      normalized_message_count,context_message_count,
                      estimated_characters)
                    VALUES (3,2,1,'legacy','[]','[]',1,1,1,12)
                    """);
            execute(connection, sql("V66__evolve_agent_context_snapshots.sql"));

            try (var row = connection.createStatement().executeQuery("""
                    SELECT revision_number,context_stage,model_snapshot,
                           context_digest FROM agent_context_snapshots
                    WHERE turn_id=3
                    """)) {
                row.next();
                assertThat(row.getInt("revision_number")).isEqualTo(1);
                assertThat(row.getString("context_stage")).isNull();
                assertThat(row.getString("model_snapshot")).isNull();
                assertThat(row.getString("context_digest")).isNull();
            }
            String digest = "a".repeat(64);
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_context_snapshots(
                      turn_id,session_id,user_id,revision_number,
                      parent_snapshot_id,context_stage,stable_stage_key,
                      revision_status,model_provider_snapshot,model_snapshot,
                      context_window_tokens,max_output_tokens,
                      token_counter_version,profile_version,total_tokens,
                      output_reserve_tokens,parent_digest,context_digest,
                      sections_json,dropped_items_json,raw_message_count,
                      normalized_message_count,context_message_count,
                      estimated_characters)
                    VALUES (3,2,1,2,1,'STEP_DECISION','step:1','READY',
                      'deepseek','deepseek-v4-flash',1000000,384000,
                      'utf8-byte-v1','layered-v1',10,50000,
                      '%s','%s','[]','[]',0,0,0,0)
                    """.formatted(digest, "b".repeat(64)));
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_context_snapshot_sections(
                      snapshot_id,section_ordinal,section_type,
                      fixed_percentage,token_limit,tokens_before,tokens_after,
                      section_status,source_refs_json,projection_json,
                      projection_digest)
                    VALUES (2,0,'CORE_AUTHORITY',10,100000,10,10,'READY',
                            '[]','{}','%s')
                    """.formatted("c".repeat(64)));
            assertThat(count(connection, "agent_context_snapshots")).isEqualTo(2);
            assertThat(count(connection, "agent_context_snapshot_sections")).isEqualTo(1);
            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    INSERT INTO agent_context_snapshots(
                      turn_id,session_id,user_id,revision_number,
                      stable_stage_key,sections_json,dropped_items_json,
                      raw_message_count,normalized_message_count,
                      context_message_count,estimated_characters)
                    VALUES (3,2,1,2,'other','[]','[]',0,0,0,0)
                    """))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    private static void createAuthorities(Connection connection) throws Exception {
        connection.createStatement().execute("CREATE TABLE sys_users(id BIGINT PRIMARY KEY)");
        connection.createStatement().execute("CREATE TABLE agent_sessions(id BIGINT PRIMARY KEY,user_id BIGINT)");
        connection.createStatement().execute("CREATE TABLE agent_turns(id BIGINT PRIMARY KEY,session_id BIGINT,user_id BIGINT)");
        connection.createStatement().execute("INSERT INTO sys_users VALUES(1)");
        connection.createStatement().execute("INSERT INTO agent_sessions VALUES(2,1)");
        connection.createStatement().execute("INSERT INTO agent_turns VALUES(3,2,1)");
    }

    private static String sql(String name) throws Exception {
        return Files.readString(Path.of(
                "src/test/resources/db/migration-h2", name));
    }

    private static void execute(Connection connection, String sql) throws Exception {
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) connection.createStatement().execute(statement);
        }
    }

    private static int count(Connection connection, String table) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
