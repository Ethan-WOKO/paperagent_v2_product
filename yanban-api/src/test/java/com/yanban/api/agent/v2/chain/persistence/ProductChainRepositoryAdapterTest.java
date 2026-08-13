package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.BindingRole;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.CommandRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextBuildFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FormattedJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.InstructionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelInvocationRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProviderAttemptRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ValidationStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.RouteDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskInstructionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskOutcomeRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionStageRecord;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainRepositoryAdapterTest {
    private static final String HASH = "0".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");

    @Test
    void v83StillReadsHistoricalCompletedOutcomeWithoutInventingTerminalRoot()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "historical-completed-outcome")) {
            ChainMigrationTestSupport.migrateThrough(connection, 73);
            String url = connection.getMetaData().getURL();
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var parameters = new org.springframework.jdbc.core.namedparam
                    .MapSqlParameterSource()
                    .addValue("hash", HASH)
                    .addValue("jsonHash",
                            ProductChainRecordCodec.sha256("[]"))
                    .addValue("createdAt", java.sql.Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO agent_v2_chain_commands(
                        command_id,user_id,session_id,client_request_id,
                        command_kind,request_sha256,turn_id,user_message_id,
                        status,created_at)
                    VALUES('legacy-command',7,8,'legacy-request','INITIAL',
                        :hash,9,10,'RECEIVED',:createdAt)
                    """, parameters);
            jdbc.update("""
                    INSERT INTO agent_v2_chain_tasks(
                        task_id,created_by_command_id,source_instruction_id,
                        user_id,session_id,turn_id,request_message_id,
                        root_client_request_id,root_request_sha256,
                        next_event_sequence,created_at)
                    VALUES('legacy-task','legacy-command','legacy-instruction',
                        7,8,9,10,'legacy-request',:hash,2,:createdAt)
                    """, parameters);
            jdbc.update("""
                    INSERT INTO agent_v2_chain_instructions(
                        instruction_id,command_id,session_id,origin_task_id,
                        message_id,body_sha256,message_identity_key,
                        relation_kind,effective_boundary_digest,created_at)
                    VALUES('legacy-instruction','legacy-command',8,
                        'legacy-task',10,:hash,'MESSAGE:10','INITIAL',
                        :hash,:createdAt)
                    """, parameters);
            jdbc.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                        event_id,task_id,event_sequence,event_type,
                        source_identity_sha256,committed_at)
                    VALUES('legacy-outcome-event','legacy-task',1,
                        'TASK_OUTCOME',:hash,:createdAt)
                    """, parameters);
            jdbc.update("""
                    INSERT INTO agent_v2_chain_task_outcomes(
                        outcome_id,task_id,event_id,source_command_id,
                        outcome_type,instruction_id,
                        coverage_format_version,coverage_sha256,coverage_json,
                        accepted_set_format_version,accepted_set_sha256,
                        accepted_set_json,candidate_key,validation_id,
                        incomplete_items_format_version,incomplete_items_sha256,
                        incomplete_items_json,limitations_format_version,
                        limitations_sha256,limitations_json,
                        risks_format_version,risks_sha256,risks_json,
                        source_decision_id,created_at)
                    VALUES('legacy-outcome','legacy-task','legacy-outcome-event',
                        'legacy-command','COMPLETED','legacy-instruction',
                        1,:jsonHash,'[]',1,:jsonHash,'[]','NONE','NONE',
                        1,:jsonHash,'[]',1,:jsonHash,'[]',1,:jsonHash,'[]',
                        'legacy-finalization',:createdAt)
                    """, parameters);
            for (int version = 74; version <= 83; version++) {
                ChainMigrationTestSupport.execute(connection,
                        ChainMigrationTestSupport.read(true,
                                ChainMigrationTestSupport.fileName(version)));
            }
            var transactions = new ProductChainTransactions(
                    jdbc, new ProductChainRecordCodec(),
                    new DataSourceTransactionManager(dataSource), () -> NOW);
            var outcome = new ProductChainFinalizationRepositoryAdapter(
                    transactions).findTaskOutcome("legacy-task")
                    .orElseThrow();

            assertEquals(ChainTaskOutcomeStatus.COMPLETED,
                    outcome.outcomeType());
            assertNull(outcome.finalizationReadinessId());
            assertNull(outcome.finalizationCheckId());
            assertNull(outcome.validationRequestDigest());
            assertNull(outcome.validationReceiptDigest());
            assertNull(outcome.publishRequirement());
            assertNull(outcome.publishRequirementDigest());
        }
    }

    @Test
    void contextBuildFailureIsAtomicReplayableAndBoundToBuildingIdentity()
            throws Exception {
        try (Harness harness = Harness.create("context-build-failure")) {
            var foundation = harness.foundation();
            foundation.registerCommand(new CommandRecord(
                    "command-context-failure", 7, 8,
                    "request-context-failure", ChainInstructionRelation.INITIAL,
                    null, null, null, HASH, 9L, 10L, null, null, null,
                    ChainCommandStatus.RECEIVED, null, NOW, null));
            foundation.appendTask(new TaskRecord(
                    "task-context-failure", "command-context-failure",
                    "instruction-context-failure", null, 7, 8, 9, 10L,
                    "request-context-failure", HASH, null, null, 0, NOW));
            foundation.appendInstruction(new InstructionRecord(
                    "instruction-context-failure", "command-context-failure",
                    8, "task-context-failure", 10L, HASH, "MESSAGE:10",
                    ChainInstructionRelation.INITIAL, null, null, HASH, NOW));
            foundation.appendTaskInstructionBinding(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-context-failure-instruction",
                            "task-context-failure", "INSTRUCTION_BOUND", null,
                            HASH, NOW),
                    new TaskInstructionBindingRecord(
                            "task-context-failure",
                            "event-context-failure-instruction",
                            "instruction-context-failure", 1,
                            BindingRole.ORIGIN, NOW)));
            ContextRevisionRecord building = new ContextRevisionRecord(
                    "context-build-failure", "task-context-failure", null,
                    ChainRole.EXECUTOR, ChainWorkState.EXECUTING, "EXECUTION",
                    "instruction-context-failure", null, null, null, null,
                    "step-context-failure", "activation-context-failure",
                    9L, "version-1", null, null, null, null, null, null,
                    "projector-v1", "pagination-v1",
                    ChainRuntimePolicy.V1.policyVersion(),
                    ChainContextRevisionStatus.BUILDING, 0, null, null, null,
                    null, null, NOW, null);
            harness.contexts().createContextRevision(building);
            ContextBuildFailureRecord failure = new ContextBuildFailureRecord(
                    "context-build-failure-record", "task-context-failure",
                    "event-context-build-failure", "context-build-failure",
                    ChainRole.EXECUTOR, ChainWorkState.EXECUTING, "EXECUTION",
                    "instruction-context-failure",
                    ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                    "CONTEXT_INPUT_BLOCKED", "projector-v1",
                    "pagination-v1", ChainRuntimePolicy.V1.policyVersion(),
                    NOW);
            var request = new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            failure.eventId(), failure.taskId(),
                            "CONTEXT_BUILD_FAILURE", null, HASH, NOW), failure);

            var first = harness.contexts().appendContextBuildFailure(request);
            var replay = harness.contexts().appendContextBuildFailure(request);

            assertFalse(first.replayed());
            assertTrue(replay.replayed());
            assertEquals(first.fact(), replay.fact());
            assertEquals(first.event(), replay.event());
            assertEquals(failure.contextBuildFailureId(),
                    harness.contexts().findContextBuildFailure(
                            building.contextRevisionId()).orElseThrow()
                            .contextBuildFailureId());
            assertEquals(failure.contextRevisionId(),
                    harness.contexts().findContextBuildFailureById(
                            failure.contextBuildFailureId()).orElseThrow()
                            .contextRevisionId());
            assertEquals(first.event().committedAt(),
                    first.fact().createdAt());

            ContextBuildFailureRecord drift = new ContextBuildFailureRecord(
                    failure.contextBuildFailureId(), failure.taskId(),
                    failure.eventId(), failure.contextRevisionId(),
                    ChainRole.EXECUTOR, ChainWorkState.EXECUTING, "OTHER",
                    failure.instructionId(), failure.failedModule(),
                    failure.errorCode(), failure.projectorSetVersion(),
                    failure.paginationVersion(), failure.runtimePolicyVersion(),
                    NOW);
            assertThrows(ProductChainPersistenceException.class,
                    () -> harness.contexts().appendContextBuildFailure(
                            new AuthoritativeFact<>(request.event(), drift)));
        }
    }

    @Test
    void authoritativeWriterOwnsAuditTimeButReplayKeepsBusinessIdentity()
            throws Exception {
        try (Harness harness = Harness.create("chain-authority-audit-time")) {
            var foundation = harness.foundation();
            foundation.registerCommand(new CommandRecord(
                    "command-audit", 7, 8, "request-audit",
                    ChainInstructionRelation.INITIAL, null, null, null, HASH,
                    9L, 10L, null, null, null,
                    ChainCommandStatus.RECEIVED, null, NOW, null));
            foundation.appendTask(new TaskRecord(
                    "task-audit", "command-audit", "instruction-audit", null,
                    7, 8, 9, 10L, "request-audit", HASH,
                    null, null, 0, NOW));
            foundation.appendInstruction(new InstructionRecord(
                    "instruction-audit", "command-audit", 8, "task-audit",
                    10L, HASH, "MESSAGE:10",
                    ChainInstructionRelation.INITIAL, null, null, HASH, NOW));
            foundation.appendTaskInstructionBinding(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-instruction-audit", "task-audit",
                            "INSTRUCTION_BOUND", null, HASH, NOW),
                    new TaskInstructionBindingRecord(
                            "task-audit", "event-instruction-audit",
                            "instruction-audit", 1L, BindingRole.ORIGIN,
                            NOW)));
            CanonicalJson empty = new CanonicalJson(
                    1, ProductChainRecordCodec.sha256("[]"), "[]");
            TaskOutcomeRecord requested = new TaskOutcomeRecord(
                    "outcome-audit", "task-audit", "event-outcome-audit",
                    "command-audit", ChainTaskOutcomeStatus.CANCELLED,
                    "instruction-audit", null, null, null,
                    empty, empty, null, ChainIdentity.NONE, ChainIdentity.NONE,
                    null, null, null, null,
                    empty, empty, empty, null, null,
                    "instruction-audit", NOW);
            AuthorityEventRequest event = new AuthorityEventRequest(
                    "event-outcome-audit", "task-audit", "TASK_OUTCOME",
                    null, HASH, NOW);

            var applied = harness.finalization().appendTaskOutcome(
                    new AuthoritativeFact<>(event, requested));
            TaskOutcomeRecord replayRequest = new TaskOutcomeRecord(
                    requested.outcomeId(), requested.taskId(), requested.eventId(),
                    requested.sourceCommandId(), requested.outcomeType(),
                    requested.instructionId(), requested.taskFrameId(),
                    requested.finalPlanId(), requested.finalPlanRevisionId(),
                    requested.coverage(), requested.acceptedSet(),
                    requested.finalArtifactId(), requested.candidateKey(),
                    requested.validationId(), requested.publishOperationId(),
                    requested.publishedProjectVersion(),
                    requested.publishedRevisionId(), requested.publishReceiptId(),
                    requested.incompleteItems(), requested.limitations(),
                    requested.risks(), requested.failureCategory(),
                    requested.failureCode(), requested.sourceDecisionId(),
                    NOW.plusSeconds(30));
            var replayed = harness.finalization().appendTaskOutcome(
                    new AuthoritativeFact<>(new AuthorityEventRequest(
                            event.eventId(), event.taskId(), event.eventType(),
                            event.transitionId(), event.sourceIdentitySha256(),
                            NOW.plusSeconds(30)), replayRequest));

            assertEquals(NOW.plusSeconds(3), applied.fact().createdAt());
            assertEquals(applied.event().committedAt(),
                    applied.fact().createdAt());
            assertEquals(applied.fact(), replayed.fact());
            assertEquals(applied.event().committedAt(),
                    replayed.event().committedAt());
            assertTrue(replayed.replayed());
        }
    }

    @Test
    void persistsAndReplaysFoundationContextAndModelAuthorities()
            throws Exception {
        try (Harness harness = Harness.create("chain-repository")) {
            ProductChainFoundationRepositoryAdapter foundation =
                    harness.foundation();
            CommandRecord command = new CommandRecord(
                    "command-1", 7, 8, "request-1",
                    ChainInstructionRelation.INITIAL, null, null, null, HASH,
                    9L, 10L, null, null, null,
                    ChainCommandStatus.RECEIVED, null, NOW, null);
            assertFalse(foundation.registerCommand(command).replayed());
            CommandRecord terminalCommand = new CommandRecord(
                    "command-terminal", 7, 8, "request-terminal",
                    ChainInstructionRelation.INITIAL, null, null, null, HASH,
                    9L, 10L, null, null, null,
                    ChainCommandStatus.FAILED, "FAILED", NOW,
                    NOW.plusSeconds(1));
            assertThrows(ProductChainPersistenceException.class,
                    () -> foundation.registerCommand(terminalCommand));
            CommandRecord retriedCommand = new CommandRecord(
                    command.commandId(), command.userId(), command.sessionId(),
                    command.clientRequestId(), command.commandKind(), null,
                    null, null, HASH, 9L, 10L, null, null, null,
                    ChainCommandStatus.RECEIVED, null,
                    NOW.plusSeconds(20), null);
            assertTrue(foundation.registerCommand(retriedCommand).replayed());

            TaskRecord task = new TaskRecord(
                    "task-1", "command-1", "instruction-1", null,
                    7, 8, 9, 10L, "request-1", HASH,
                    null, null, 0, NOW);
            foundation.appendTask(task);
            InstructionRecord instruction = new InstructionRecord(
                    "instruction-1", "command-1", 8, "task-1", 10L,
                    HASH, "MESSAGE:10", ChainInstructionRelation.INITIAL,
                    null, null, HASH, NOW);
            foundation.appendInstruction(instruction);

            TaskInstructionBindingRecord binding =
                    new TaskInstructionBindingRecord(
                            "task-1", "event-1", "instruction-1", 1,
                            BindingRole.ORIGIN, NOW);
            var event = new AuthorityEventRequest(
                    "event-1", "task-1", "INSTRUCTION_BOUND", null,
                    HASH, NOW);
            var applied = foundation.appendTaskInstructionBinding(
                    new AuthoritativeFact<>(event, binding));
            var replayed = foundation.appendTaskInstructionBinding(
                    new AuthoritativeFact<>(new AuthorityEventRequest(
                            "event-1", "task-1", "INSTRUCTION_BOUND", null,
                            HASH, NOW.plusSeconds(20)),
                            new TaskInstructionBindingRecord(
                                    "task-1", "event-1", "instruction-1", 1,
                                    BindingRole.ORIGIN,
                                    NOW.plusSeconds(20))));
            assertFalse(applied.replayed());
            assertTrue(replayed.replayed());
            ProductChainPersistenceException wrongType = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> foundation.appendTaskInstructionBinding(
                            new AuthoritativeFact<>(
                                    new AuthorityEventRequest(
                                            "event-wrong-type", "task-1",
                                            "PROPOSAL_ACCEPTED", null,
                                            HASH, NOW),
                                    new TaskInstructionBindingRecord(
                                            "task-1", "event-wrong-type",
                                            "instruction-1", 2,
                                            BindingRole.ORIGIN, NOW))));
            assertEquals("CHAIN_AUTHORITY_EVENT_TYPE_MISMATCH",
                    wrongType.code());
            ProductChainPersistenceException wrongTransition = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> harness.workflow().appendTransitionStage(
                            new AuthoritativeFact<>(
                                    new AuthorityEventRequest(
                                            "event-wrong-transition",
                                            "task-1", "TRANSITION_STAGE",
                                            "transition-other", HASH, NOW),
                                    new TransitionStageRecord(
                                            "transition-1",
                                            ChainTransitionStage.OPEN,
                                            "task-1",
                                            "event-wrong-transition", 0,
                                            null, null, null, null, NOW))));
            assertEquals("CHAIN_AUTHORITY_EVENT_TRANSITION_MISMATCH",
                    wrongTransition.code());
            assertEquals(1, applied.event().eventSequence());
            assertEquals(1, foundation.highestAuthorityEventSequence("task-1"));
            assertEquals(1,
                    foundation.findTaskInstructions("task-1", 1).size());
            assertEquals("command-1", foundation.findCommand("command-1")
                    .orElseThrow().commandId());
            assertEquals("instruction-1",
                    foundation.findInstruction("instruction-1")
                            .orElseThrow().instructionId());
            assertEquals(List.of("event-1"),
                    foundation.findAuthorityEvents("task-1", 1).stream()
                            .map(eventRecord -> eventRecord.eventId())
                            .toList());
            assertEquals(1, foundation.appendTask(task).value()
                    .nextEventSequence());
            assertThrows(ProductChainPersistenceException.class,
                    () -> foundation.commitCommand(
                            "command-1", "task-1", "event-1", "missing"));
            assertEquals(ChainCommandStatus.COMMITTED,
                    foundation.commitCommand("command-1", "task-1",
                            "event-1", "instruction-1").status());
            assertEquals(ChainCommandStatus.COMMITTED,
                    foundation.registerCommand(command).value().status());
            CommandRecord changedDigest = new CommandRecord(
                    command.commandId(), command.userId(), command.sessionId(),
                    command.clientRequestId(), command.commandKind(), null,
                    null, null, "1".repeat(64), 9L, 10L,
                    null, null, null, ChainCommandStatus.RECEIVED,
                    null, NOW, null);
            assertThrows(ProductChainPersistenceException.class,
                    () -> foundation.registerCommand(changedDigest));

            ChainIdentity.Transition transitionIdentity =
                    new ChainIdentity.Transition(
                            ChainTransitionType.GAP_RESOLUTION,
                            "task-1", "decision-1", HASH);
            TransitionRecord transition = new TransitionRecord(
                    transitionIdentity.transitionId(), "task-1",
                    "event-transition", ChainTransitionType.GAP_RESOLUTION,
                    "decision-1", HASH, NOW);
            var transitionApplied = harness.workflow().appendTransition(
                    new AuthoritativeFact<>(new AuthorityEventRequest(
                            "event-transition", "task-1", "TRANSITION",
                            transition.transitionId(), HASH, NOW),
                            transition));
            TransitionStageRecord openStage = new TransitionStageRecord(
                    transition.transitionId(), ChainTransitionStage.OPEN,
                    "task-1", "event-transition-open", 0,
                    null, null, null, null, NOW);
            var openApplied = harness.workflow().appendTransitionStage(
                    new AuthoritativeFact<>(new AuthorityEventRequest(
                            "event-transition-open", "task-1",
                            "TRANSITION_STAGE", transition.transitionId(),
                            HASH, NOW), openStage));
            var openReplayed = harness.workflow().appendTransitionStage(
                    new AuthoritativeFact<>(new AuthorityEventRequest(
                            "event-transition-open", "task-1",
                            "TRANSITION_STAGE", transition.transitionId(),
                            HASH, NOW.plusSeconds(5)),
                            new TransitionStageRecord(
                                    transition.transitionId(),
                                    ChainTransitionStage.OPEN, "task-1",
                                    "event-transition-open", 0,
                                    null, null, null, null,
                                    NOW.plusSeconds(5))));
            assertEquals(2, transitionApplied.event().eventSequence());
            assertEquals(3, openApplied.event().eventSequence());
            assertTrue(openReplayed.replayed());
            TransitionStageRecord skippedComplete =
                    new TransitionStageRecord(
                            transition.transitionId(),
                            ChainTransitionStage.COMPLETE, "task-1",
                            "event-transition-skip", 1,
                            null, null, null, null, NOW);
            ProductChainPersistenceException skipped = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> harness.workflow().appendTransitionStage(
                            new AuthoritativeFact<>(
                                    new AuthorityEventRequest(
                                            "event-transition-skip",
                                            "task-1", "TRANSITION_STAGE",
                                            transition.transitionId(),
                                            HASH, NOW),
                                    skippedComplete)));
            assertEquals("CHAIN_TRANSITION_STAGE_NOT_NEXT", skipped.code());

            ProductChainContextRepositoryAdapter contexts = harness.contexts();
            ContextRevisionRecord building = context(
                    ChainContextRevisionStatus.BUILDING, 0, null, null,
                    null, null);
            contexts.createContextRevision(building);
            String jsonHash = ProductChainRecordCodec.sha256("{}");
            CanonicalJson json = new CanonicalJson(1, jsonHash, "{}");
            List<ContextModuleRecord> moduleRecords = new ArrayList<>();
            for (ChainContextModule module : ChainContextModule.values()) {
                ContextModuleRecord moduleRecord = new ContextModuleRecord(
                        "context-1", "task-1", module.ordinalCode(), module,
                        ChainContextModuleStatus.EMPTY, json, json,
                        "projector-v1", "pagination-v1", json, json, NOW);
                moduleRecords.add(moduleRecord);
                contexts.appendContextModule(moduleRecord);
            }
            ProductChainContextManifestCodec manifestCodec =
                    harness.manifests();
            FormattedJson manifest = manifestCodec.manifest(moduleRecords);
            ContextRevisionRecord complete = context(
                    ChainContextRevisionStatus.COMPLETE, 13,
                    manifest, ProductChainRecordCodec.sha256(
                            manifestCodec.canonicalPrompt(moduleRecords)),
                    "completion-1", NOW.plusSeconds(1));
            assertEquals(ChainContextRevisionStatus.COMPLETE,
                    contexts.completeContextRevision(complete).status());
            assertEquals(ChainContextRevisionStatus.COMPLETE,
                    contexts.createContextRevision(building).value().status());
            assertEquals(13, contexts.findContextModules("context-1").size());

            ContextRevisionRecord incomplete = context(
                    "context-incomplete", ChainContextRevisionStatus.BUILDING,
                    0, null, null, null, null, null, null);
            contexts.createContextRevision(incomplete);
            List<ContextModuleRecord> incompleteModules = appendModules(
                    contexts, "context-incomplete", 12, json);
            assertEquals(12, incompleteModules.size());
            ContextRevisionRecord invalidComplete = context(
                    "context-incomplete", ChainContextRevisionStatus.COMPLETE,
                    13, manifest, manifestCodec.digest(manifest),
                    "completion-incomplete", null, null,
                    NOW.plusSeconds(1));
            assertThrows(ProductChainPersistenceException.class,
                    () -> contexts.completeContextRevision(invalidComplete));

            ContextRevisionRecord tampered = context(
                    "context-tampered", ChainContextRevisionStatus.BUILDING,
                    0, null, null, null, null, null, null);
            contexts.createContextRevision(tampered);
            List<ContextModuleRecord> tamperedModules = appendModules(
                    contexts, "context-tampered", 13, json);
            FormattedJson canonicalTampered =
                    manifestCodec.manifest(tamperedModules);
            FormattedJson alteredManifest = new FormattedJson(1,
                    canonicalTampered.json().replace(
                            "projector-v1", "projector-v2"));
            ContextRevisionRecord alteredComplete = context(
                    "context-tampered", ChainContextRevisionStatus.COMPLETE,
                    13, alteredManifest,
                    manifestCodec.digest(alteredManifest),
                    "completion-tampered", null, null,
                    NOW.plusSeconds(1));
            assertThrows(ProductChainPersistenceException.class,
                    () -> contexts.completeContextRevision(alteredComplete));

            ContextRevisionRecord blockedBuilding = context(
                    "context-blocked", ChainContextRevisionStatus.BUILDING,
                    0, null, null, null, null, null, null);
            contexts.createContextRevision(blockedBuilding);
            List<ContextModuleRecord> blockedModules = appendModules(
                    contexts, "context-blocked", 13, json);
            FormattedJson blockedManifest =
                    manifestCodec.manifest(blockedModules);
            ContextRevisionRecord blocked = context(
                    "context-blocked",
                    ChainContextRevisionStatus.INPUT_BLOCKED,
                    13, blockedManifest, null, null,
                    "MISSING_INPUT",
                    ProductChainRecordCodec.sha256(
                            manifestCodec.canonicalPrompt(blockedModules)),
                    NOW.plusSeconds(1));
            assertEquals(ChainContextRevisionStatus.INPUT_BLOCKED,
                    contexts.blockContextRevision(blocked).status());
            assertEquals(4,
                    contexts.findContextRevisions("task-1").size());

            ContextRevisionRecord wrongCompleteDigestBuilding = context(
                    "context-wrong-complete-digest",
                    ChainContextRevisionStatus.BUILDING,
                    0, null, null, null, null, null, null);
            contexts.createContextRevision(wrongCompleteDigestBuilding);
            List<ContextModuleRecord> wrongCompleteDigestModules =
                    appendModules(contexts,
                            "context-wrong-complete-digest", 13, json);
            FormattedJson wrongCompleteDigestManifest =
                    manifestCodec.manifest(wrongCompleteDigestModules);
            ContextRevisionRecord wrongCompleteDigest = context(
                    "context-wrong-complete-digest",
                    ChainContextRevisionStatus.COMPLETE,
                    13, wrongCompleteDigestManifest, HASH,
                    "completion-wrong-digest", null, null,
                    NOW.plusSeconds(1));
            ProductChainPersistenceException rejectedCompleteDigest =
                    assertThrows(ProductChainPersistenceException.class,
                            () -> contexts.completeContextRevision(
                                    wrongCompleteDigest));
            assertEquals("CHAIN_CONTEXT_MANIFEST_DIGEST_MISMATCH",
                    rejectedCompleteDigest.code());

            ContextRevisionRecord wrongBlockedDigestBuilding = context(
                    "context-wrong-blocked-digest",
                    ChainContextRevisionStatus.BUILDING,
                    0, null, null, null, null, null, null);
            contexts.createContextRevision(wrongBlockedDigestBuilding);
            List<ContextModuleRecord> wrongBlockedDigestModules =
                    appendModules(contexts,
                            "context-wrong-blocked-digest", 13, json);
            FormattedJson wrongBlockedDigestManifest =
                    manifestCodec.manifest(wrongBlockedDigestModules);
            ContextRevisionRecord wrongBlockedDigest = context(
                    "context-wrong-blocked-digest",
                    ChainContextRevisionStatus.INPUT_BLOCKED,
                    13, wrongBlockedDigestManifest, null, null,
                    "CONTEXT_INPUT_BLOCKED", HASH,
                    NOW.plusSeconds(1));
            ProductChainPersistenceException rejectedBlockedDigest =
                    assertThrows(ProductChainPersistenceException.class,
                            () -> contexts.blockContextRevision(
                                    wrongBlockedDigest));
            assertEquals("CHAIN_CONTEXT_MANIFEST_DIGEST_MISMATCH",
                    rejectedBlockedDigest.code());

            ProductChainModelRepositoryAdapter model = harness.model();
            model.appendInvocation(new ModelInvocationRecord(
                    "invocation-1", "task-1", "context-1", "completion-1",
                    ChainRole.PLANNER, ChainWorkState.PLANNING, "INITIAL",
                    "provider", "model", 1, "policy-v1", NOW));
            model.appendProviderAttempt(new ProviderAttemptRecord(
                    "invocation-1", 1, "task-1", 1L, "STOP",
                    ValidationStatus.PASSED,
                    ValidationStatus.PASSED,
                    null, NOW));
            model.appendContent(new ContentRecord(
                    "content-1", "task-1", "invocation-1",
                    ChainContentKind.ANSWER_BODY, "answer",
                    ProductChainRecordCodec.sha256("answer"),
                    "text/plain", NOW));
            ModelProposalRecord proposal = new ModelProposalRecord(
                    "proposal-1", "task-1", "invocation-1", 1,
                    ChainRole.PLANNER,
                    ChainProposalKind.PLANNER_DIRECT_ROUTE,
                    json, json, null, null, NOW);
            model.appendProposal(proposal);
            ProposalStateEventRecord accepted =
                    new ProposalStateEventRecord(
                            "proposal-1", 1, "task-1", "event-2",
                            ChainProposalState.ACCEPTED, null,
                            null, NOW.plusSeconds(2));
            var state = model.appendProposalState(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-2", "task-1", "PROPOSAL_ACCEPTED", null,
                            HASH, NOW.plusSeconds(2)), accepted));
            assertEquals(4, state.event().eventSequence());
            assertTrue(model.appendProposalState(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-2", "task-1", "PROPOSAL_ACCEPTED", null,
                            HASH, NOW.plusSeconds(20)),
                    new ProposalStateEventRecord(
                            "proposal-1", 1, "task-1", "event-2",
                            ChainProposalState.ACCEPTED, null, null,
                            NOW.plusSeconds(20)))).replayed());
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-1", 3, "task-1", "event-state-skip",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "ROUTE_DECISION", "route-1", NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-1", 2, "task-1", "event-state-wrong-next",
                    ChainProposalState.REJECTED, null, null, NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-1", 2, "task-1", "event-state-missing-ref",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    null, null, NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-1", 2, "task-1",
                    "event-state-missing-authority",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "ROUTE_DECISION", "missing-route", NOW),
                    "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID");
            assertEquals(4,
                    foundation.highestAuthorityEventSequence("task-1"));
            RouteDecisionRecord routeDecision = new RouteDecisionRecord(
                    "route-1", "task-1", "event-route",
                    "instruction-1", "proposal-1",
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .RouteDecisionType.INITIAL,
                    0, io.paperagent.v2.chain.ChainExecutionMode.DIRECT,
                    "direct", json, json, json,
                    false, false, false, false,
                    null, null, null, NOW.plusSeconds(3));
            harness.workflow().appendRouteDecision(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-route", "task-1", "ROUTE_DECISION",
                            null, HASH, NOW.plusSeconds(3)), routeDecision));
            ProposalStateEventRecord replaced =
                    new ProposalStateEventRecord(
                            "proposal-1", 2, "task-1", "event-3",
                            ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                            "ROUTE_DECISION", "route-1",
                            NOW.plusSeconds(3));
            var official = model.appendProposalState(new AuthoritativeFact<>(
                    proposalEvent(replaced), replaced));
            assertFalse(official.replayed());
            assertTrue(model.appendProposalState(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-3", "task-1",
                            "PROPOSAL_REPLACED_BY_OFFICIAL_RESULT", null,
                            HASH, NOW.plusSeconds(30)),
                    new ProposalStateEventRecord(
                            "proposal-1", 2, "task-1", "event-3",
                            ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                            "ROUTE_DECISION", "route-1",
                            NOW.plusSeconds(30)))).replayed());
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-1", 3, "task-1", "event-state-duplicate",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "ROUTE_DECISION", "route-1", NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");

            appendProposal(model, json, "rejected", 2);
            ProposalStateEventRecord rejected = new ProposalStateEventRecord(
                    "proposal-rejected", 1, "task-1", "event-rejected",
                    ChainProposalState.REJECTED, null, null, NOW);
            model.appendProposalState(new AuthoritativeFact<>(
                    proposalEvent(rejected), rejected));
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-rejected", 2, "task-1",
                    "event-rejected-replaced",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "PLAN_BINDING", "plan-binding-1", NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");

            appendProposal(model, json, "stale", 3);
            ProposalStateEventRecord stale = new ProposalStateEventRecord(
                    "proposal-stale", 1, "task-1", "event-stale",
                    ChainProposalState.STALE, null, null, NOW);
            model.appendProposalState(new AuthoritativeFact<>(
                    proposalEvent(stale), stale));
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-stale", 2, "task-1", "event-stale-replaced",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "ACTION_BINDING", "action-1", NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");

            appendProposal(model, json, "invalid", 4);
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-invalid", 1, "task-1",
                    "event-initial-replaced",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "DELIVERY", "delivery-1", NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-invalid", 1, "task-1",
                    "event-initial-official-ref",
                    ChainProposalState.ACCEPTED,
                    "ROUTE_DECISION", "route-1", NOW),
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT");
            ProposalStateEventRecord acceptedForAuthorityChecks =
                    new ProposalStateEventRecord(
                            "proposal-invalid", 1, "task-1",
                            "event-invalid-accepted",
                            ChainProposalState.ACCEPTED, null, null, NOW);
            model.appendProposalState(new AuthoritativeFact<>(
                    proposalEvent(acceptedForAuthorityChecks),
                    acceptedForAuthorityChecks));
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-invalid", 2, "task-1",
                    "event-unknown-authority",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "UNKNOWN_AUTHORITY", "unknown-1", NOW),
                    "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_TYPE_UNSUPPORTED");
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-invalid", 2, "task-1",
                    "event-nonexistent-authority",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "TASK_OUTCOME", "missing-outcome", NOW),
                    "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID");
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-invalid", 2, "task-1",
                    "event-wrong-proposal-authority",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "ROUTE_DECISION", "route-1", NOW),
                    "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID");

            foundation.registerCommand(new CommandRecord(
                    "command-cross", 7, 8, "request-cross",
                    ChainInstructionRelation.INITIAL,
                    null, null, null, HASH, 12L, 11L,
                    null, null, null, ChainCommandStatus.RECEIVED,
                    null, NOW, null));
            foundation.appendTask(new TaskRecord(
                    "task-cross", "command-cross", "instruction-cross",
                    null, 7, 8, 12, 11L, "request-cross", HASH,
                    null, null, 0, NOW));
            foundation.appendInstruction(new InstructionRecord(
                    "instruction-cross", "command-cross", 8,
                    "task-cross", 11L, HASH, "MESSAGE:11",
                    ChainInstructionRelation.INITIAL,
                    null, null, HASH, NOW));
            TaskInstructionBindingRecord crossBinding =
                    new TaskInstructionBindingRecord(
                            "task-cross", "event-bind-cross",
                            "instruction-cross", 1,
                            BindingRole.ORIGIN, NOW);
            foundation.appendTaskInstructionBinding(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-bind-cross", "task-cross",
                            "INSTRUCTION_BOUND", null, HASH, NOW),
                    crossBinding));

            ContextRevisionRecord crossInstructionContext = context(
                    "context-cross-instruction", "instruction-cross",
                    ChainContextRevisionStatus.BUILDING, 0,
                    null, null, null, null, null, null);
            ProductChainPersistenceException crossContext = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> contexts.createContextRevision(
                            crossInstructionContext));
            assertEquals("CHAIN_INSTRUCTION_TASK_MISMATCH",
                    crossContext.code());

            RouteDecisionRecord crossInstructionRoute =
                    new RouteDecisionRecord(
                            "route-cross-instruction", "task-1",
                            "event-route-cross-instruction",
                            "instruction-cross", "proposal-1",
                            io.paperagent.v2.chain.ChainPersistenceRecords
                                    .RouteDecisionType.INITIAL,
                            0, io.paperagent.v2.chain.ChainExecutionMode.DIRECT,
                            "direct", json, json, json,
                            false, false, false, false,
                            null, null, null, NOW);
            ProductChainPersistenceException crossRoute = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> harness.workflow().appendRouteDecision(
                            new AuthoritativeFact<>(
                                    new AuthorityEventRequest(
                                            "event-route-cross-instruction",
                                            "task-1", "ROUTE_DECISION",
                                            null, HASH, NOW),
                                    crossInstructionRoute)));
            assertEquals("CHAIN_INSTRUCTION_TASK_MISMATCH",
                    crossRoute.code());

            TaskOutcomeRecord wrongCommandOutcome = new TaskOutcomeRecord(
                    "outcome-wrong-command", "task-1",
                    "event-outcome-wrong-command", "command-cross",
                    ChainTaskOutcomeStatus.CANCELLED, "instruction-1",
                    null, null, null, json, json, null,
                    ChainIdentity.NONE, ChainIdentity.NONE,
                    null, null, null, null, json, json, json,
                    null, null, "decision-wrong-command", NOW);
            ProductChainPersistenceException crossCommand = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> harness.finalization().appendTaskOutcome(
                            new AuthoritativeFact<>(
                                    new AuthorityEventRequest(
                                            "event-outcome-wrong-command",
                                            "task-1", "TASK_OUTCOME",
                                            null, HASH, NOW),
                                    wrongCommandOutcome)));
            assertEquals("CHAIN_COMMAND_TASK_MISMATCH",
                    crossCommand.code());
            TaskOutcomeRecord crossTaskOutcome = new TaskOutcomeRecord(
                    "outcome-cross", "task-cross", "event-outcome-cross",
                    "command-cross", ChainTaskOutcomeStatus.CANCELLED,
                    "instruction-cross", null, null, null,
                    json, json, null, ChainIdentity.NONE,
                    ChainIdentity.NONE, null, null, null, null,
                    json, json, json, null, null, "decision-cross", NOW);
            harness.finalization().appendTaskOutcome(
                    new AuthoritativeFact<>(
                            new AuthorityEventRequest(
                                    "event-outcome-cross", "task-cross",
                                    "TASK_OUTCOME", null, HASH, NOW),
                            crossTaskOutcome));
            assertStateRejected(model, new ProposalStateEventRecord(
                    "proposal-invalid", 2, "task-1",
                    "event-cross-task-authority",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "TASK_OUTCOME", "outcome-cross", NOW),
                    "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID");
            assertEquals(1,
                    model.findProposalStateEvents("proposal-invalid").size());
            assertEquals(ChainProposalKind.PLANNER_DIRECT_ROUTE,
                    model.findProposal("proposal-1").orElseThrow()
                            .proposalKind());
            assertEquals(1, model.findInvocations("task-1", 1).size());
            assertEquals(4, model.highestInvocationOrdinal("task-1"));
            assertEquals(1,
                    model.highestProviderAttemptNo("invocation-1"));
            assertEquals(1, model.findContents("invocation-1").size());
            assertEquals("content-1", model.findContent("content-1")
                    .orElseThrow().contentId());
            assertEquals("proposal-1",
                    model.findProposalByInvocation("invocation-1")
                            .orElseThrow().proposalId());
            assertEquals(2,
                    model.findProposalStateEvents("proposal-1").size());
            assertEquals(List.of("route-1"),
                    harness.workflow().findRouteDecisions("task-1").stream()
                            .map(RouteDecisionRecord::routeDecisionId)
                            .toList());
            try (var statement = harness.connection().createStatement()) {
                statement.executeUpdate("UPDATE "
                        + "agent_v2_chain_model_proposals "
                        + "SET payload_json = '{\"tampered\":true}' "
                        + "WHERE proposal_id = 'proposal-1'");
            }
            assertThrows(ProductChainPersistenceException.class,
                    () -> model.findProposal("proposal-1"));
            try (var statement = harness.connection().createStatement()) {
                statement.executeUpdate("UPDATE agent_v2_chain_contents "
                        + "SET body = 'tampered' "
                        + "WHERE content_id = 'content-1'");
            }
            assertThrows(ProductChainPersistenceException.class,
                    () -> model.findContent("content-1"));
        }
    }

    @Test
    void recoveryReadsDeliveryAndDeliveryEventsFromFormalFacts()
            throws Exception {
        try (Harness harness = Harness.create("chain-delivery-read")) {
            ProductChainFoundationRepositoryAdapter foundation =
                    harness.foundation();
            foundation.registerCommand(new CommandRecord(
                    "command-delivery", 7, 8, "request-delivery",
                    ChainInstructionRelation.INITIAL,
                    null, null, null, HASH, 9L, 10L,
                    null, null, null, ChainCommandStatus.RECEIVED,
                    null, NOW, null));
            foundation.appendTask(new TaskRecord(
                    "task-delivery", "command-delivery",
                    "instruction-delivery", null,
                    7, 8, 9, 10L, "request-delivery", HASH,
                    null, null, 0, NOW));
            foundation.appendInstruction(new InstructionRecord(
                    "instruction-delivery", "command-delivery", 8,
                    "task-delivery", 10L, HASH, "MESSAGE:10",
                    ChainInstructionRelation.INITIAL,
                    null, null, HASH, NOW));

            DeliveryRecord delivery = new DeliveryRecord(
                    "delivery-1", "task-delivery", "event-delivery",
                    "command-delivery", null, null, null,
                    "decision-1", null, null, NOW);
            harness.finalization().appendDelivery(new AuthoritativeFact<>(
                    new AuthorityEventRequest(
                            "event-delivery", "task-delivery", "DELIVERY",
                            null, HASH, NOW), delivery));
            DeliveryEventRecord pending = new DeliveryEventRecord(
                    "delivery-1", 1, "task-delivery",
                    "event-delivery-pending", ChainDeliveryStatus.PENDING,
                    0, null, ChainRuntimePolicy.V1.policyVersion(), NOW);
            harness.finalization().appendDeliveryEvent(
                    new AuthoritativeFact<>(new AuthorityEventRequest(
                            "event-delivery-pending", "task-delivery",
                            "DELIVERY_PENDING", null, HASH, NOW), pending));

            assertEquals(List.of("delivery-1"),
                    harness.finalization().findDeliveries("task-delivery")
                            .stream().map(DeliveryRecord::deliveryId)
                            .toList());
            assertEquals(List.of(ChainDeliveryStatus.PENDING),
                    harness.finalization().findDeliveryEvents("delivery-1")
                            .stream().map(DeliveryEventRecord::eventKind)
                            .toList());
        }
    }

    private static ContextRevisionRecord context(
            ChainContextRevisionStatus status, int moduleCount,
            FormattedJson manifest, String requestDigest,
            String completionToken, Instant completedAt) {
        return context("context-1", status, moduleCount, manifest,
                requestDigest, completionToken, null, null, completedAt);
    }

    private static ContextRevisionRecord context(
            String contextRevisionId, ChainContextRevisionStatus status,
            int moduleCount, FormattedJson manifest, String requestDigest,
            String completionToken, String blockedErrorCode,
            String inputDigest, Instant completedAt) {
        return context(contextRevisionId, "instruction-1", status,
                moduleCount, manifest, requestDigest, completionToken,
                blockedErrorCode, inputDigest, completedAt);
    }

    private static ContextRevisionRecord context(
            String contextRevisionId, String instructionId,
            ChainContextRevisionStatus status, int moduleCount,
            FormattedJson manifest, String requestDigest,
            String completionToken, String blockedErrorCode,
            String inputDigest, Instant completedAt) {
        return new ContextRevisionRecord(
                contextRevisionId, "task-1", null, ChainRole.PLANNER,
                ChainWorkState.PLANNING, "INITIAL", instructionId,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "projectors-v1",
                "pagination-v1", "policy-v1", status, moduleCount,
                manifest, requestDigest, completionToken, blockedErrorCode,
                inputDigest,
                NOW, completedAt);
    }

    private static List<ContextModuleRecord> appendModules(
            ProductChainContextRepositoryAdapter contexts,
            String contextRevisionId, int count, CanonicalJson json) {
        List<ContextModuleRecord> records = new ArrayList<>();
        ChainContextModule[] modules = ChainContextModule.values();
        for (int index = 0; index < count; index++) {
            ChainContextModule module = modules[index];
            ContextModuleRecord record = new ContextModuleRecord(
                    contextRevisionId, "task-1", module.ordinalCode(), module,
                    ChainContextModuleStatus.EMPTY, json, json,
                    "projector-v1", "pagination-v1", json, json, NOW);
            contexts.appendContextModule(record);
            records.add(record);
        }
        return records;
    }

    private static void appendProposal(
            ProductChainModelRepositoryAdapter model, CanonicalJson json,
            String suffix, int invocationOrdinal) {
        String invocationId = "invocation-" + suffix;
        model.appendInvocation(new ModelInvocationRecord(
                invocationId, "task-1", "context-1", "completion-1",
                ChainRole.PLANNER, ChainWorkState.PLANNING, "INITIAL",
                "provider", "model", invocationOrdinal, "policy-v1", NOW));
        model.appendProposal(new ModelProposalRecord(
                "proposal-" + suffix, "task-1", invocationId, 1,
                ChainRole.PLANNER, ChainProposalKind.PLANNER_DIRECT_ROUTE,
                json, json, null, null, NOW));
    }

    private static AuthorityEventRequest proposalEvent(
            ProposalStateEventRecord state) {
        return new AuthorityEventRequest(
                state.eventId(), state.taskId(),
                "PROPOSAL_" + state.stateKind().name(), null,
                HASH, state.committedAt());
    }

    private static void assertStateRejected(
            ProductChainModelRepositoryAdapter model,
            ProposalStateEventRecord state, String expectedCode) {
        ProductChainPersistenceException rejected = assertThrows(
                ProductChainPersistenceException.class,
                () -> model.appendProposalState(new AuthoritativeFact<>(
                        proposalEvent(state), state)));
        assertEquals(expectedCode, rejected.code());
    }

    private record Harness(
            Connection connection,
            ProductChainFoundationRepositoryAdapter foundation,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter model,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainContextManifestCodec manifests)
            implements AutoCloseable {
        static Harness create(String label) throws Exception {
            Connection connection = ChainMigrationTestSupport.database(label);
            ChainMigrationTestSupport.migrateThrough(connection, 83);
            String url = connection.getMetaData().getURL();
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var transactions = new ProductChainTransactions(
                    jdbc, new ProductChainRecordCodec(),
                    new DataSourceTransactionManager(dataSource),
                    () -> NOW.plusSeconds(3));
            var manifestCodec = new ProductChainContextManifestCodec(
                    new com.fasterxml.jackson.databind.ObjectMapper());
            return new Harness(connection,
                    new ProductChainFoundationRepositoryAdapter(
                            transactions, () -> NOW.plusSeconds(3)),
                    new ProductChainContextRepositoryAdapter(
                            transactions, manifestCodec),
                    new ProductChainModelRepositoryAdapter(transactions),
                    new ProductChainWorkflowRepositoryAdapter(transactions),
                    new ProductChainFinalizationRepositoryAdapter(
                            transactions),
                    manifestCodec);
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }
}
