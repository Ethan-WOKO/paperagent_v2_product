package com.yanban.api.agent.v2.chain.persistence;

import com.yanban.api.agent.v2.chain.recovery.ProductChainReceivedCommandSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainReceivedCommandSourceTest {
    private static final String ROOT_BODY = "root request";
    private static final String ROOT_BODY_SHA = sha256(ROOT_BODY);

    @Test
    void readsAndScansAnExactReceivedRootWithoutWriting() throws Exception {
        try (Harness harness = Harness.create("received-root")) {
            var found = harness.source().findExact(
                    7L, 8L, "request-1", ChainMigrationTestSupport.HASH)
                    .orElseThrow();

            assertThat(found.commandId()).isEqualTo("command-1");
            assertThat(found.taskId()).isEqualTo("task-1");
            assertThat(found.instructionId()).isEqualTo("instruction-1");
            assertThat(found.instructionBindingEventId())
                    .isEqualTo("event-1");
            assertThat(found.turnId()).isEqualTo(9L);
            assertThat(found.messageId()).isEqualTo(10L);
            assertThat(found.rootClientRequestId()).isEqualTo("request-1");
            assertThat(harness.source().scan(null, 10).entries()).hasSize(1);
            var scan = (ProductChainReceivedCommandSource.Ready)
                    harness.source().scan(null, 10).entries().get(0);
            assertThat(scan.command()).isEqualTo(found);
            assertThat(harness.commandStatus("command-1"))
                    .isEqualTo("RECEIVED");
        }
    }

    @Test
    void aFreshRecoverySourceAfterRestartSeesTheSameReceivedCut()
            throws Exception {
        try (Harness harness = Harness.create("received-after-restart")) {
            var beforeRestart = harness.source().findByCommandId("command-1")
                    .orElseThrow();

            var afterRestart = new ProductChainReceivedCommandSource(
                    harness.jdbc()).findByCommandId("command-1")
                    .orElseThrow();

            assertThat(afterRestart).isEqualTo(beforeRestart);
            assertThat(harness.commandStatus("command-1"))
                    .isEqualTo("RECEIVED");
        }
    }

    @Test
    void exactLookupRejectsRequestDigestDrift() throws Exception {
        try (Harness harness = Harness.create("received-digest")) {
            assertThatThrownBy(() -> harness.source().findExact(
                    7L, 8L, "request-1", "1".repeat(64)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("received command request digest changed");
        }
    }

    @Test
    void terminalCommandsAreNotReceivedRecoveryCandidates() throws Exception {
        try (Harness harness = Harness.create("received-terminal")) {
            harness.commitRoot();

            assertThat(harness.source().findByCommandId("command-1"))
                    .isEmpty();
            assertThat(harness.source().scan(null, 10).entries()).isEmpty();
        }
    }

    @Test
    void poisonedFirstCommandDoesNotStarveTheNextValidCommand()
            throws Exception {
        try (Harness harness = Harness.create("received-poison-isolation")) {
            ChainMigrationTestSupport.seedSecondFoundation(
                    harness.connection());
            harness.addConversation(17L, 18L, 19L, 20L, "valid second");
            harness.update("""
                    UPDATE agent_v2_chain_commands
                       SET turn_id = 19, user_message_id = 20
                     WHERE command_id = 'command-2'
                    """);
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET request_message_id = 20
                     WHERE task_id = 'task-2'
                    """);
            harness.jdbc().update("""
                    UPDATE agent_v2_chain_instructions
                       SET body_sha256 = :bodySha
                     WHERE instruction_id = 'instruction-2'
                    """, Map.of("bodySha", sha256("valid second")));
            harness.update("""
                    UPDATE agent_messages SET role = 'assistant' WHERE id = 10
                    """);

            var results = harness.source().scan(null, 10).entries();

            assertThat(results).hasSize(2);
            var blocked = (ProductChainReceivedCommandSource.Blocked)
                    results.get(0);
            var ready = (ProductChainReceivedCommandSource.Ready)
                    results.get(1);
            assertThat(blocked.commandId()).isEqualTo("command-1");
            assertThat(blocked.errorCode())
                    .isEqualTo("CHAIN_RECEIVED_COMMAND_READ_BLOCKED");
            assertThat(blocked.reason())
                    .isEqualTo("received command message owner changed");
            assertThat(ready.command().commandId()).isEqualTo("command-2");
            assertThat(harness.commandStatus("command-1"))
                    .isEqualTo("RECEIVED");
            assertThat(harness.commandStatus("command-2"))
                    .isEqualTo("RECEIVED");
        }
    }

    @Test
    void cursorAdvancesPastAFullBlockedPageToTheNextReadyCommand()
            throws Exception {
        try (Harness harness = Harness.create("received-cursor-isolation")) {
            harness.commitRoot();
            for (int index = 0; index < 100; index++) {
                harness.jdbc().update("""
                        INSERT INTO agent_v2_chain_commands(
                          command_id,user_id,session_id,client_request_id,
                          command_kind,request_sha256,status,created_at)
                        VALUES (:commandId,7,8,:requestId,'INITIAL',
                          REPEAT('3',64),'RECEIVED',
                          TIMESTAMP '2026-08-08 08:00:00')
                        """, new MapSqlParameterSource()
                        .addValue("commandId", "poison-%03d".formatted(index))
                        .addValue("requestId", "poison-request-" + index));
            }
            ChainMigrationTestSupport.seedSecondFoundation(
                    harness.connection());
            harness.addConversation(17L, 18L, 19L, 20L, "ready after page");
            harness.update("""
                    UPDATE agent_v2_chain_commands
                       SET turn_id = 19, user_message_id = 20,
                           created_at = TIMESTAMP '2026-08-08 08:01:00'
                     WHERE command_id = 'command-2'
                    """);
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET request_message_id = 20
                     WHERE task_id = 'task-2'
                    """);
            harness.jdbc().update("""
                    UPDATE agent_v2_chain_instructions
                       SET body_sha256 = :bodySha
                     WHERE instruction_id = 'instruction-2'
                    """, Map.of("bodySha", sha256("ready after page")));

            var first = harness.source().scan(null, 100);
            var second = harness.source().scan(first.nextCursor(), 100);

            assertThat(first.entries()).hasSize(100)
                    .allMatch(ProductChainReceivedCommandSource.Blocked.class
                            ::isInstance);
            assertThat(first.hasMore()).isTrue();
            assertThat(second.entries()).hasSize(1);
            assertThat(second.entries().get(0))
                    .isInstanceOf(ProductChainReceivedCommandSource.Ready.class);
            var ready = (ProductChainReceivedCommandSource.Ready)
                    second.entries().get(0);
            assertThat(ready.commandId()).isEqualTo("command-2");
            assertThat(second.hasMore()).isFalse();
        }
    }

    @Test
    void supplementWithoutANewTaskUsesOnlyItsExactTargetRoot()
            throws Exception {
        try (Harness harness = Harness.create("received-supplement")) {
            harness.commitRoot();
            harness.addConversation(19L, 20L, "supplement request");
            harness.update("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,target_task_id,target_client_request_id,
                      request_sha256,turn_id,user_message_id,status,created_at)
                    VALUES ('command-2',7,8,'request-2','SUPPLEMENT',
                      'task-1','request-1',REPEAT('2',64),19,20,
                      'RECEIVED',CURRENT_TIMESTAMP)
                    """);
            harness.addInstruction("instruction-2", "command-2", "task-1",
                    20L, "SUPPLEMENT", "supplement request", 2L, "event-2");

            var found = harness.source().findExact(
                    7L, 8L, "request-2", "2".repeat(64)).orElseThrow();

            assertThat(found.taskId()).isEqualTo("task-1");
            assertThat(found.instructionId()).isEqualTo("instruction-2");
            assertThat(found.instructionBindingEventId())
                    .isEqualTo("event-2");
            assertThat(found.rootClientRequestId()).isEqualTo("request-1");
            assertThat(found.rootRequestSha256())
                    .isEqualTo(ChainMigrationTestSupport.HASH);
        }
    }

    @Test
    void supplementIsBlockedWhenANewerInstructionIsCurrent()
            throws Exception {
        try (Harness harness = Harness.create("received-supplement-current")) {
            harness.commitRoot();
            harness.addConversation(19L, 20L, "supplement request");
            harness.update("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,target_task_id,target_client_request_id,
                      request_sha256,turn_id,user_message_id,status,created_at)
                    VALUES ('command-2',7,8,'request-2','SUPPLEMENT',
                      'task-1','request-1',REPEAT('2',64),19,20,
                      'RECEIVED',CURRENT_TIMESTAMP)
                    """);
            harness.addInstruction("instruction-2", "command-2", "task-1",
                    20L, "SUPPLEMENT", "supplement request", 2L, "event-2");
            harness.addOtherCommand("command-3", "request-3");
            harness.addInstruction("instruction-3", "command-3", "task-1",
                    10L, "SUPPLEMENT", "later instruction", 3L, "event-3");

            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("received command instruction is not current");
        }
    }

    @Test
    void initialCommandIsBlockedWhenTaskHasAnotherInstructionBinding()
            throws Exception {
        try (Harness harness = Harness.create("received-initial-bindings")) {
            harness.addOtherCommand("command-2", "request-2");
            harness.addInstruction("instruction-2", "command-2", "task-1",
                    10L, "SUPPLEMENT", "later instruction", 2L, "event-2");

            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("initial task has additional instruction bindings");
        }
    }

    @Test
    void explicitReplacementRequiresRetainedBindingsOutcomeAndParent()
            throws Exception {
        try (Harness harness = Harness.create("received-replacement")) {
            harness.commitRoot();
            harness.addConversation(29L, 30L, "replacement request");
            harness.update("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,target_task_id,target_client_request_id,
                      request_sha256,turn_id,user_message_id,status,created_at)
                    VALUES ('command-2',7,8,'request-2','REPLACEMENT',
                      'task-1','request-1',REPEAT('2',64),29,30,
                      'RECEIVED',CURRENT_TIMESTAMP)
                    """);
            harness.update("""
                    INSERT INTO agent_v2_chain_tasks(
                      task_id,created_by_command_id,source_instruction_id,
                      predecessor_task_id,user_id,session_id,turn_id,
                      request_message_id,root_client_request_id,
                      root_request_sha256,next_event_sequence,created_at)
                    VALUES ('task-2','command-2','instruction-2','task-1',
                      7,8,29,30,'request-2',REPEAT('2',64),0,
                      CURRENT_TIMESTAMP)
                    """);
            harness.addBinding("task-2", "instruction-1", 1L,
                    "task-2-event-1", "INHERITED_ROOT");
            harness.addInstruction("instruction-2", "command-2", "task-2",
                    30L, "REPLACEMENT", "replacement request", 2L,
                    "task-2-event-2");
            harness.update("""
                    UPDATE agent_v2_chain_instructions
                       SET parent_instruction_id = 'instruction-1'
                     WHERE instruction_id = 'instruction-2'
                    """);
            harness.addSupersededOutcome();

            var found = harness.source().findByCommandId("command-2")
                    .orElseThrow();

            assertThat(found.taskId()).isEqualTo("task-2");
            assertThat(found.targetTaskId()).isEqualTo("task-1");
            assertThat(found.rootClientRequestId()).isEqualTo("request-2");
            assertThat(found.progressionRelation().name())
                    .isEqualTo("INITIAL");

            harness.update("""
                    UPDATE agent_v2_chain_task_instruction_bindings
                       SET task_instruction_sequence = 3
                     WHERE task_id = 'task-2'
                       AND instruction_id = 'instruction-2'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("explicit replacement intake binding changed");
            harness.update("""
                    UPDATE agent_v2_chain_task_instruction_bindings
                       SET task_instruction_sequence = 2
                     WHERE task_id = 'task-2'
                       AND instruction_id = 'instruction-2'
                    """);
            harness.update("""
                    UPDATE agent_v2_chain_task_outcomes
                       SET source_decision_id = 'different-instruction'
                     WHERE task_id = 'task-1'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("formal boundary replacement outcome changed");
            harness.update("""
                    UPDATE agent_v2_chain_task_outcomes
                       SET source_decision_id = 'instruction-2'
                     WHERE task_id = 'task-1'
                    """);
            harness.update("""
                    UPDATE agent_v2_chain_instructions
                       SET parent_instruction_id = 'different-instruction'
                     WHERE instruction_id = 'instruction-2'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("explicit replacement parent is not the old boundary");
        }
    }

    @Test
    void dispositionReplacementUsesFullProofAndRejectsProofDrift()
            throws Exception {
        try (Harness harness = Harness.create("received-disposition")) {
            harness.commitRoot();
            harness.addConversation(19L, 20L, "boundary supplement");
            harness.update("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,target_task_id,target_client_request_id,
                      request_sha256,turn_id,user_message_id,status,created_at)
                    VALUES ('command-2',7,8,'request-2','SUPPLEMENT',
                      'task-1','request-1',REPEAT('2',64),19,20,
                      'RECEIVED',CURRENT_TIMESTAMP)
                    """);
            harness.addInstruction("instruction-2", "command-2", "task-1",
                    20L, "SUPPLEMENT", "boundary supplement", 2L, "event-2");
            harness.update("""
                    UPDATE agent_v2_chain_instructions
                       SET parent_instruction_id = 'instruction-1'
                     WHERE instruction_id = 'instruction-2'
                    """);
            harness.addDispositionProposal();
            harness.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES ('event-3','task-1',3,'INSTRUCTION_DISPOSITION',
                      REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            harness.update("""
                    INSERT INTO agent_v2_chain_instruction_dispositions(
                      disposition_id,task_id,event_id,proposal_id,
                      instruction_id,classification,old_task_disposition,
                      reply_required,continuation_or_reintake_position,
                      boundary_changed,applicability_format_version,
                      applicability_sha256,applicability_json,
                      non_authoritative_reuse_suggestions_format_version,
                      non_authoritative_reuse_suggestions_sha256,
                      non_authoritative_reuse_suggestions_json,created_at)
                    VALUES ('disposition-1','task-1','event-3','proposal-2',
                      'instruction-2','BOUNDARY_CHANGE','SUPERSEDE',0,
                      'REINTAKE',1,1,REPEAT('0',64),'[]',1,
                      REPEAT('0',64),'[]',CURRENT_TIMESTAMP)
                    """);
            harness.addSupersededOutcome();
            harness.update("""
                    INSERT INTO agent_v2_chain_tasks(
                      task_id,created_by_command_id,source_instruction_id,
                      predecessor_task_id,user_id,session_id,turn_id,
                      request_message_id,root_client_request_id,
                      root_request_sha256,next_event_sequence,created_at)
                    VALUES ('task-2','command-2','instruction-2','task-1',
                      7,8,19,20,'request-2',REPEAT('2',64),0,
                      CURRENT_TIMESTAMP)
                    """);
            harness.addBinding("task-2", "instruction-2", 1L,
                    "task-2-event-1", "INHERITED_ROOT");

            var found = harness.source().findByCommandId("command-2")
                    .orElseThrow();

            assertThat(found.taskId()).isEqualTo("task-2");
            assertThat(found.instructionId()).isEqualTo("instruction-2");
            assertThat(found.instructionBindingEventId())
                    .isEqualTo("task-2-event-1");
            assertThat(found.commandKind().name()).isEqualTo("SUPPLEMENT");
            assertThat(found.progressionRelation().name())
                    .isEqualTo("INITIAL");

            harness.update("""
                    UPDATE agent_v2_chain_task_outcomes
                       SET source_decision_id = 'different-instruction'
                     WHERE task_id = 'task-1'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("formal boundary replacement outcome changed");
            harness.update("""
                    UPDATE agent_v2_chain_task_outcomes
                       SET source_decision_id = 'instruction-2'
                     WHERE task_id = 'task-1'
                    """);
            harness.update("""
                    UPDATE agent_v2_chain_instructions
                       SET parent_instruction_id = 'different-instruction'
                     WHERE instruction_id = 'instruction-2'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boundary disposition trigger or old boundary is not current");
            harness.update("""
                    UPDATE agent_v2_chain_instructions
                       SET parent_instruction_id = 'instruction-1'
                     WHERE instruction_id = 'instruction-2'
                    """);

            harness.update("""
                    DELETE FROM agent_v2_chain_instruction_dispositions
                     WHERE disposition_id = 'disposition-1'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boundary disposition authority is missing");
        }
    }

    @Test
    void fallbackRejectsCommandKindsThatDoNotResumePlannerProgression()
            throws Exception {
        try (Harness harness = Harness.create("received-answer")) {
            harness.commitRoot();
            harness.addConversation(19L, 20L, "pending answer");
            harness.update("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,target_task_id,target_client_request_id,
                      gap_id,request_sha256,turn_id,user_message_id,status,
                      created_at)
                    VALUES ('command-2',7,8,'request-2',
                      'ANSWER_TO_PENDING_ITEM','task-1','request-1','gap-1',
                      REPEAT('2',64),19,20,'RECEIVED',CURRENT_TIMESTAMP)
                    """);

            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("received command has no recoverable task authority");
        }
    }

    @Test
    void rejectsDriftedMessageIdentity() throws Exception {
        try (Harness harness = Harness.create("received-message-drift")) {
            harness.update("""
                    UPDATE agent_messages SET role = 'assistant' WHERE id = 10
                    """);

            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("received command message owner changed");
        }
    }

    @Test
    void rejectsDriftedTaskOwnerAndRootIdentity() throws Exception {
        try (Harness harness = Harness.create("received-task-drift")) {
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET user_id = 17
                     WHERE task_id = 'task-1'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("received command task owner changed");
        }
        try (Harness harness = Harness.create("received-root-drift")) {
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET root_request_sha256 = REPEAT('1',64)
                     WHERE task_id = 'task-1'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("created task command identity changed");
        }
    }

    @Test
    void rejectsDriftedTurnAndInstructionOrigin() throws Exception {
        try (Harness harness = Harness.create("received-turn-drift")) {
            harness.update("""
                    UPDATE agent_turns SET user_message_id = 77 WHERE id = 9
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("received command turn identity changed");
        }
        try (Harness harness = Harness.create("received-origin-drift")) {
            harness.update("""
                    UPDATE agent_v2_chain_instructions
                       SET origin_task_id = 'different-task'
                     WHERE instruction_id = 'instruction-1'
                    """);
            assertThatThrownBy(() -> harness.source()
                    .findByCommandId("command-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("received command instruction origin changed");
        }
    }

    @Test
    void duplicateCommandRowsFailClosed() {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        Map<String, Object> command = Map.ofEntries(
                Map.entry("command_id", "command-1"),
                Map.entry("user_id", 7L),
                Map.entry("session_id", 8L),
                Map.entry("client_request_id", "request-1"),
                Map.entry("command_kind", "INITIAL"),
                Map.entry("request_sha256", "0".repeat(64)),
                Map.entry("status", "RECEIVED"));
        when(jdbc.queryForList(anyString(),
                any(MapSqlParameterSource.class)))
                .thenReturn(List.of(command, command));

        assertThatThrownBy(() -> new ProductChainReceivedCommandSource(jdbc)
                .findByCommandId("command-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("received command identity is ambiguous");
    }

    private record Harness(
            Connection connection,
            NamedParameterJdbcTemplate jdbc,
            ProductChainReceivedCommandSource source) implements AutoCloseable {
        static Harness create(String label) throws Exception {
            Connection connection = ChainMigrationTestSupport.database(label);
            createConversationTables(connection);
            ChainMigrationTestSupport.migrateThrough(connection, 75);
            ChainMigrationTestSupport.seedFoundation(connection);
            var dataSource = new DriverManagerDataSource(
                    connection.getMetaData().getURL(), "sa", "");
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var harness = new Harness(connection, jdbc,
                    new ProductChainReceivedCommandSource(jdbc));
            harness.addConversation(9L, 10L, ROOT_BODY);
            harness.update("""
                    UPDATE agent_v2_chain_commands
                       SET turn_id = 9, user_message_id = 10
                     WHERE command_id = 'command-1'
                    """);
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET request_message_id = 10
                     WHERE task_id = 'task-1'
                    """);
            jdbc.update("""
                    UPDATE agent_v2_chain_instructions
                       SET body_sha256 = :bodySha
                     WHERE instruction_id = 'instruction-1'
                    """, Map.of("bodySha", ROOT_BODY_SHA));
            return harness;
        }

        private static void createConversationTables(Connection connection)
                throws Exception {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE agent_messages(
                          id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
                          session_id BIGINT NOT NULL, role VARCHAR(32) NOT NULL,
                          content LONGTEXT)
                        """);
                statement.execute("""
                        CREATE TABLE agent_turns(
                          id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
                          session_id BIGINT NOT NULL,
                          user_message_id BIGINT NOT NULL)
                        """);
            }
        }

        void addConversation(long turnId, long messageId, String body) {
            addConversation(7L, 8L, turnId, messageId, body);
        }

        void addConversation(
                long userId,
                long sessionId,
                long turnId,
                long messageId,
                String body) {
            jdbc.update("""
                    INSERT INTO agent_messages(
                      id,user_id,session_id,role,content)
                    VALUES (:messageId,:userId,:sessionId,'user',:body)
                    """, new MapSqlParameterSource()
                    .addValue("messageId", messageId)
                    .addValue("userId", userId)
                    .addValue("sessionId", sessionId)
                    .addValue("body", body));
            jdbc.update("""
                    INSERT INTO agent_turns(
                      id,user_id,session_id,user_message_id)
                    VALUES (:turnId,:userId,:sessionId,:messageId)
                    """, new MapSqlParameterSource()
                    .addValue("turnId", turnId)
                    .addValue("userId", userId)
                    .addValue("sessionId", sessionId)
                    .addValue("messageId", messageId));
        }

        void addInstruction(
                String instructionId,
                String commandId,
                String taskId,
                long messageId,
                String relation,
                String body,
                long sequence,
                String eventId) {
            jdbc.update("""
                    INSERT INTO agent_v2_chain_instructions(
                      instruction_id,command_id,session_id,origin_task_id,
                      message_id,body_sha256,message_identity_key,
                      relation_kind,effective_boundary_digest,created_at)
                    VALUES (:instructionId,:commandId,8,:taskId,:messageId,
                      :bodySha,:identity,:relation,REPEAT('0',64),
                      CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("instructionId", instructionId)
                    .addValue("commandId", commandId)
                    .addValue("taskId", taskId)
                    .addValue("messageId", messageId)
                    .addValue("bodySha", sha256(body))
                    .addValue("identity", "command:" + commandId)
                    .addValue("relation", relation));
            addBinding(taskId, instructionId, sequence, eventId, "ORIGIN");
        }

        void addOtherCommand(String commandId, String clientRequestId) {
            jdbc.update("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,request_sha256,status,created_at)
                    VALUES (:commandId,7,8,:clientRequestId,'INITIAL',
                      REPEAT('2',64),'RECEIVED',CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("commandId", commandId)
                    .addValue("clientRequestId", clientRequestId));
        }

        void addBinding(
                String taskId,
                String instructionId,
                long sequence,
                String eventId,
                String role) {
            jdbc.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES (:eventId,:taskId,:sequence,'INSTRUCTION_BOUND',
                      REPEAT('0',64),CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("eventId", eventId)
                    .addValue("taskId", taskId)
                    .addValue("sequence", sequence));
            jdbc.update("""
                    INSERT INTO agent_v2_chain_task_instruction_bindings(
                      task_id,event_id,instruction_id,
                      task_instruction_sequence,relation_role,created_at)
                    VALUES (:taskId,:eventId,:instructionId,:sequence,
                      :role,CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("taskId", taskId)
                    .addValue("eventId", eventId)
                    .addValue("instructionId", instructionId)
                    .addValue("sequence", sequence)
                    .addValue("role", role));
        }

        void addDispositionProposal() {
            update("""
                    INSERT INTO agent_v2_chain_context_revisions(
                      context_revision_id,task_id,role,work_state,call_reason,
                      instruction_id,projector_set_version,pagination_version,
                      runtime_policy_version,status,module_count,
                      request_manifest_format_version,request_manifest_json,
                      request_digest,completion_token,created_at,completed_at)
                    VALUES ('context-2','task-1','PLANNER','PLANNING',
                      'USER_INSTRUCTION_DISPOSITION','instruction-2',
                      'projectors-v1','pagination-v1',
                      'chain-runtime-policy-v1','COMPLETE',13,1,'{}',
                      REPEAT('0',64),'completion-2',CURRENT_TIMESTAMP,
                      CURRENT_TIMESTAMP)
                    """);
            update("""
                    INSERT INTO agent_v2_chain_model_invocations(
                      invocation_id,task_id,context_revision_id,
                      completion_token,role,work_state,call_reason,provider,
                      model,invocation_ordinal,runtime_policy_version,created_at)
                    VALUES ('invocation-2','task-1','context-2','completion-2',
                      'PLANNER','PLANNING','USER_INSTRUCTION_DISPOSITION',
                      'fake','fake',1,'chain-runtime-policy-v1',
                      CURRENT_TIMESTAMP)
                    """);
            update("""
                    INSERT INTO agent_v2_chain_model_proposals(
                      proposal_id,task_id,invocation_id,schema_version,role,
                      proposal_kind,payload_format_version,payload_sha256,
                      payload_json,source_refs_format_version,
                      source_refs_sha256,source_refs_json,created_at)
                    VALUES ('proposal-2','task-1','invocation-2',1,'PLANNER',
                      'USER_INSTRUCTION_DISPOSITION',1,
                      REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                      CURRENT_TIMESTAMP)
                    """);
        }

        void addSupersededOutcome() {
            update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES ('event-4','task-1',4,'TASK_OUTCOME',
                      REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            update("""
                    INSERT INTO agent_v2_chain_task_outcomes(
                      outcome_id,task_id,event_id,source_command_id,
                      outcome_type,instruction_id,coverage_format_version,
                      coverage_sha256,coverage_json,accepted_set_format_version,
                      accepted_set_sha256,accepted_set_json,candidate_key,
                      validation_id,incomplete_items_format_version,
                      incomplete_items_sha256,incomplete_items_json,
                      limitations_format_version,limitations_sha256,
                      limitations_json,risks_format_version,risks_sha256,
                      risks_json,source_decision_id,created_at)
                    VALUES ('outcome-1','task-1','event-4','command-2',
                      'SUPERSEDED','instruction-1',1,REPEAT('0',64),'[]',
                      1,REPEAT('0',64),'[]','NONE','NONE',1,
                      REPEAT('0',64),'[]',1,REPEAT('0',64),'[]',1,
                      REPEAT('0',64),'[]','instruction-2',CURRENT_TIMESTAMP)
                    """);
        }

        void commitRoot() {
            update("""
                    UPDATE agent_v2_chain_commands
                       SET result_task_id = 'task-1',
                           result_event_id = 'event-1',
                           result_instruction_id = 'instruction-1',
                           status = 'COMMITTED', committed_at = CURRENT_TIMESTAMP
                     WHERE command_id = 'command-1'
                    """);
        }

        String commandStatus(String commandId) {
            return jdbc.queryForObject("""
                    SELECT status FROM agent_v2_chain_commands
                     WHERE command_id = :commandId
                    """, Map.of("commandId", commandId), String.class);
        }

        void update(String sql) {
            jdbc.getJdbcTemplate().execute(sql);
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
