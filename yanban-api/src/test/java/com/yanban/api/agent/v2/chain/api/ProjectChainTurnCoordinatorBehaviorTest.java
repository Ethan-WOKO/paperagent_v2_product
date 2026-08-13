package com.yanban.api.agent.v2.chain.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.v2.chain.context.ProductChainSkillSnapshotService;
import com.yanban.api.agent.v2.chain.context.ProductValidationPublishContextProjector;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainReceivedCommandSource;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainCommandWriter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionWriter;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskWriter;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Direct behavior evidence for the short Project-chain API boundary. */
class ProjectChainTurnCoordinatorBehaviorTest {
    private static final long USER = 7L;
    private static final long SESSION = 8L;
    private static final long PROJECT = 9L;
    private static final String REQUEST = "request-1";

    private final Map<String, ChainPersistenceRecords.CommandRecord> commands =
            new LinkedHashMap<>();
    private final Map<String, ChainPersistenceRecords.TaskRecord> tasks =
            new LinkedHashMap<>();
    private final Map<String, ChainPersistenceRecords.InstructionRecord> instructions =
            new LinkedHashMap<>();
    private final Map<String, List<ChainPersistenceRecords.TaskInstructionBindingRecord>> bindings =
            new LinkedHashMap<>();
    private final Map<String, List<ChainPersistenceRecords.AuthorityEventRecord>> events =
            new LinkedHashMap<>();
    private final Map<Long, AgentMessage> messagesById = new LinkedHashMap<>();
    private final Map<Long, AgentTurn> turnsById = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    private ProjectChainTurnCoordinator coordinator;
    private ProjectChainPlannerProgression planner;
    private ChainFinalizationRepository finalization;
    private ProjectChainTurnEntryTransactions transactions;
    private ProductValidationPublishContextProjector terminalValidations;
    private com.yanban.api.agent.v2.persistence
            .ProductChainStepAuthorityAdapter stepAuthorities;

    @BeforeEach
    void setUp() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentTurnRepository turns = mock(AgentTurnRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectChainSessionCommandLock commandLock =
                mock(ProjectChainSessionCommandLock.class);
        var logger = mock(com.yanban.api.agent.v2.chain.observability
                .ProjectChainSafeLogger.class);
        ChainFoundationRepository foundations = foundation();
        ChainCommandWriter commandWriter = commandWriter();
        ChainTaskWriter taskWriter = taskWriter();
        ChainInstructionWriter instructionWriter = instructionWriter();
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        ChainPendingItemWriter pendingWriter = mock(ChainPendingItemWriter.class);
        finalization = mock(ChainFinalizationRepository.class);
        ProductChainCompletedOutcomeAdapter outcomes =
                mock(ProductChainCompletedOutcomeAdapter.class);
        ChainModelRepository models = mock(ChainModelRepository.class);
        stepAuthorities = mock(com.yanban.api.agent.v2.persistence
                .ProductChainStepAuthorityAdapter.class);
        StepRecoveryRepository recovery = mock(StepRecoveryRepository.class);
        CandidateChangeArtifactService artifacts =
                mock(CandidateChangeArtifactService.class);
        terminalValidations = mock(
                ProductValidationPublishContextProjector.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        planner = mock(ProjectChainPlannerProgression.class);
        ProductChainSkillSnapshotService skills =
                mock(ProductChainSkillSnapshotService.class);
        transactions = mock(ProjectChainTurnEntryTransactions.class);

        AgentSession session = new AgentSession(USER, "project", "provider",
                "model", 8, true, AgentSessionScope.PROJECT, PROJECT);
        setId(session, SESSION);
        when(sessions.findByIdAndUserId(SESSION, USER))
                .thenReturn(Optional.of(session));
        when(messages.saveAndFlush(any(AgentMessage.class))).thenAnswer(call -> {
            AgentMessage value = call.getArgument(0);
            long id = ids.incrementAndGet();
            setId(value, id);
            messagesById.put(id, value);
            return value;
        });
        when(messages.findById(anyLong())).thenAnswer(call ->
                Optional.ofNullable(messagesById.get(call.getArgument(0))));
        when(turns.saveAndFlush(any(AgentTurn.class))).thenAnswer(call -> {
            AgentTurn value = call.getArgument(0);
            long id = ids.incrementAndGet();
            setId(value, id);
            turnsById.put(id, value);
            return value;
        });
        when(turns.findById(anyLong())).thenAnswer(call ->
                Optional.ofNullable(turnsById.get(call.getArgument(0))));
        when(projects.manifest(USER, PROJECT)).thenReturn(
                new ProjectManifestResponse(PROJECT, "project-v1", List.of()));
        when(skills.preservesSelection(anyString(), any()))
                .thenReturn(true);

        emptyWorkflow(workflow);
        when(finalization.findTaskOutcome(anyString()))
                .thenReturn(Optional.empty());
        when(finalization.findDeliveries(anyString())).thenReturn(List.of());
        when(finalization.findDeliveryEvents(anyString())).thenReturn(List.of());
        when(jdbc.queryForList(anyString(),
                any(SqlParameterSource.class), any(Class.class)))
                .thenAnswer(call -> new ArrayList<>(tasks.keySet()));
        when(planner.advance(any(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ProjectChainPlannerProgression.ProgressionResult(
                        false, "route-event-1"));
        when(transactions.inBeginWrite(any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(0)).get());
        when(transactions.inPublicCutWrite(any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(0)).get());

        coordinator = new ProjectChainTurnCoordinator(
                sessions, messages, turns, projects, commandLock, logger,
                foundations, commandWriter, taskWriter, instructionWriter,
                workflow, pendingWriter, finalization, outcomes, models,
                stepAuthorities, recovery, artifacts, terminalValidations, jdbc,
                planner, skills, transactions);
    }

    @Test
    void publicStepProjectionReadsTheExactFormalReplannedRevision() {
        var binding = mock(
                ChainPersistenceRecords.PlanBindingRecord.class);
        var revision = mock(io.paperagent.v2.contracts.PlanRevision.class);
        when(binding.planRevisionId()).thenReturn("revision-2");
        when(stepAuthorities.findPlanRevision(
                "task-replanned", "revision-2"))
                .thenReturn(Optional.of(revision));

        assertThat(coordinator.formalPlanRevisionForProjection(
                "task-replanned", binding)).isSameAs(revision);
        verify(stepAuthorities).findPlanRevision(
                "task-replanned", "revision-2");
    }

    @Test
    void committedFirstCutIsImmediatelyGettableAndListable() {
        var response = coordinator.start(USER, SESSION, request());

        assertThat(response.clientRequestId()).isEqualTo(REQUEST);
        assertThat(response.rootClientRequestId()).isEqualTo(REQUEST);
        assertThat(response.replayed()).isFalse();
        assertThat(coordinator.get(USER, SESSION, REQUEST).workState())
                .isEqualTo("PLANNING");
        assertThat(coordinator.list(USER, SESSION, 20))
                .extracting(V2ProjectTurnListItem::clientRequestId)
                .containsExactly(REQUEST);
        assertThat(commands.values()).singleElement().satisfies(command -> {
            assertThat(command.status()).isEqualTo(ChainCommandStatus.COMMITTED);
            assertThat(command.resultTaskId()).isNotBlank();
            assertThat(command.resultInstructionId()).isNotBlank();
            assertThat(command.resultEventId()).startsWith(
                    "instruction-bound.");
        });
        verify(planner, times(0)).advance(
                any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void publicTurnUsesGenericTerminalValidationSummary() {
        coordinator.start(USER, SESSION, request());
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        when(outcome.outcomeType()).thenReturn(
                ChainTaskOutcomeStatus.COMPLETED);
        when(outcome.validationId()).thenReturn("validation-1");
        when(outcome.finalArtifactId()).thenReturn(null);
        when(outcome.publishedRevisionId()).thenReturn(null);
        when(finalization.findTaskOutcome(anyString()))
                .thenReturn(Optional.of(outcome));
        when(terminalValidations.terminalValidation(any(), any(), isNull()))
                .thenReturn(new ProductValidationPublishContextProjector
                        .TerminalValidation(
                        "validation-1", "PASSED", "1".repeat(64),
                        "2".repeat(64), List.of(
                        new ProductValidationPublishContextProjector
                                .TerminalReceipt(
                                "requirement-1", "ACTION_RECEIPT",
                                "receipt-1", "action-1", null, null,
                                null))));

        var response = coordinator.get(USER, SESSION, REQUEST);

        assertThat(response.validation().validationId())
                .isEqualTo("validation-1");
        assertThat(response.validation().receipts())
                .containsExactly(new V2ProjectTurnResponse.ValidationReceipt(
                        "requirement-1", "ACTION_RECEIPT", "receipt-1",
                        "action-1", null, null, null));
    }

    @Test
    void exactClientRequestReplayReturnsTheSamePublicCutWithoutCallingPlannerAgain() {
        var first = coordinator.start(USER, SESSION, request());
        var replay = coordinator.start(USER, SESSION, request());

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.rootClientRequestId())
                .isEqualTo(first.rootClientRequestId());
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.userMessageId()).isEqualTo(first.userMessageId());
        assertThat(commands).hasSize(1);
        assertThat(messagesById).hasSize(1);
        verify(planner, times(0)).advance(
                any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void terminalTaskRejectsBothANewInstructionAndCancellationBeforeSideEffects() {
        coordinator.start(USER, SESSION, request());
        when(finalization.findTaskOutcome(anyString())).thenReturn(
                Optional.of(mock(
                        ChainPersistenceRecords.TaskOutcomeRecord.class)));

        assertThatThrownBy(() -> coordinator.start(USER, SESSION,
                new V2NaturalLanguageTurnRequest(
                        "late correction", false, null, "request-2",
                        "CORRECTION", REQUEST)))
                .isInstanceOfSatisfying(ProjectChainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CHAIN_TASK_TERMINAL"));
        assertThatThrownBy(() -> coordinator.cancel(
                USER, SESSION, REQUEST,
                new V2TurnCancelRequest("request-3")))
                .isInstanceOfSatisfying(ProjectChainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CHAIN_TASK_TERMINAL"));
        assertThat(commands).hasSize(1);
        assertThat(messagesById).hasSize(1);
        verify(planner, times(0)).advance(
                any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void continuationKeepsTheRootVisibleAndExactReplayAddsNoSecondBodyOrTask() {
        coordinator.start(USER, SESSION, request());
        var correction = new V2NaturalLanguageTurnRequest(
                "narrow the objective", false, null, "request-2",
                "CORRECTION", REQUEST);

        var first = coordinator.start(USER, SESSION, correction);
        var replay = coordinator.start(USER, SESSION, correction);

        assertThat(coordinator.get(USER, SESSION, REQUEST).clientRequestId())
                .isEqualTo(REQUEST);
        assertThat(first.rootClientRequestId()).isEqualTo(REQUEST);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(commands).hasSize(2);
        assertThat(tasks).hasSize(1);
        assertThat(messagesById).hasSize(2);
        verify(planner, times(1)).advance(
                any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void explicitReplacementPublishesItsKnownNewBoundaryBeforePlanner() {
        coordinator.start(USER, SESSION, request());
        var replacement = new V2NaturalLanguageTurnRequest(
                "replace the objective", false, null, "request-2",
                "REPLACEMENT", REQUEST);

        var response = coordinator.start(USER, SESSION, replacement);
        var replay = coordinator.start(USER, SESSION, replacement);

        assertThat(response.rootClientRequestId()).isEqualTo("request-2");
        assertThat(response.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(coordinator.get(USER, SESSION, "request-2").workState())
                .isEqualTo("PLANNING");
        assertThat(tasks).hasSize(2);
        assertThat(messagesById).hasSize(2);
        verify(planner, times(0)).advance(
                any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void legacyReceivedInitialCutIsPublishedBeforePlannerOnRecovery() {
        coordinator.start(USER, SESSION, request());
        ChainPersistenceRecords.CommandRecord command = commands.values()
                .iterator().next();
        ChainPersistenceRecords.CommandRecord receivedCommand =
                new ChainPersistenceRecords.CommandRecord(
                        command.commandId(), command.userId(),
                        command.sessionId(), command.clientRequestId(),
                        command.commandKind(), command.targetTaskId(),
                        command.targetClientRequestId(), command.gapId(),
                        command.requestSha256(), command.turnId(),
                        command.userMessageId(), null, null, null,
                        ChainCommandStatus.RECEIVED, null,
                        command.createdAt(), null);
        commands.put(command.commandId(), receivedCommand);
        ChainPersistenceRecords.TaskRecord task = tasks.values()
                .iterator().next();
        ChainPersistenceRecords.InstructionRecord instruction = instructions
                .get(task.sourceInstructionId());
        String bindingEvent = bindings.get(task.taskId()).get(0).eventId();
        var received = new ProductChainReceivedCommandSource.ReceivedCommand(
                command.commandId(), task.taskId(), instruction.instructionId(),
                bindingEvent, USER, SESSION, REQUEST,
                command.requestSha256(), ChainInstructionRelation.INITIAL,
                ChainInstructionRelation.INITIAL, command.turnId(),
                command.userMessageId(), task.rootClientRequestId(),
                task.rootRequestSha256(), null, null);

        var recovered = coordinator.resumeReceivedPlanner(received);

        assertThat(recovered.rootClientRequestId()).isEqualTo(REQUEST);
        assertThat(commands.get(command.commandId()).status())
                .isEqualTo(ChainCommandStatus.COMMITTED);
        assertThat(coordinator.get(USER, SESSION, REQUEST).workState())
                .isEqualTo("PLANNING");
        verify(planner, times(0)).advance(
                any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void committedInitialCutSurvivesResponseFailureAndExactDuplicateReplays() {
        org.mockito.Mockito.doThrow(
                        new IllegalStateException("response connection lost"))
                .doAnswer(call -> ((Supplier<?>) call.getArgument(0)).get())
                .when(transactions).inPublicCutWrite(any());
        assertThatThrownBy(() -> coordinator.start(
                USER, SESSION, request()))
                .hasMessage("response connection lost");
        assertThat(commands.values()).singleElement().satisfies(command ->
                assertThat(command.status())
                        .isEqualTo(ChainCommandStatus.COMMITTED));
        assertThat(coordinator.get(USER, SESSION, REQUEST).workState())
                .isEqualTo("PLANNING");

        var replay = coordinator.start(USER, SESSION, request());

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.rootClientRequestId()).isEqualTo(REQUEST);
        assertThat(commands).hasSize(1);
        assertThat(tasks).hasSize(1);
        assertThat(messagesById).hasSize(1);
        verify(planner, times(0)).advance(
                any(), any(), any(), anyString(), any(), any());
    }

    private V2NaturalLanguageTurnRequest request() {
        return new V2NaturalLanguageTurnRequest(
                "inspect the project", false, null, REQUEST, null, null);
    }

    private ChainFoundationRepository foundation() {
        ChainFoundationRepository value = mock(ChainFoundationRepository.class);
        when(value.findCommand(anyLong(), anyLong(), anyString())).thenAnswer(call ->
                commands.values().stream().filter(command ->
                        command.userId() == (long) call.getArgument(0)
                                && command.sessionId() == (long) call.getArgument(1)
                                && command.clientRequestId().equals(call.getArgument(2)))
                        .findFirst());
        when(value.findCommand(anyString())).thenAnswer(call ->
                Optional.ofNullable(commands.get(call.getArgument(0))));
        when(value.findTask(anyString())).thenAnswer(call ->
                Optional.ofNullable(tasks.get(call.getArgument(0))));
        when(value.findInstruction(anyString())).thenAnswer(call ->
                Optional.ofNullable(instructions.get(call.getArgument(0))));
        when(value.findTaskInstructions(anyString(), anyLong())).thenAnswer(call ->
                List.copyOf(bindings.getOrDefault(call.getArgument(0), List.of())));
        when(value.findAuthorityEvents(anyString(), anyLong())).thenAnswer(call ->
                List.copyOf(events.getOrDefault(call.getArgument(0), List.of())));
        when(value.highestAuthorityEventSequence(anyString())).thenAnswer(call ->
                (long) events.getOrDefault(call.getArgument(0), List.of()).size());
        return value;
    }

    private ChainCommandWriter commandWriter() {
        ChainCommandWriter value = mock(ChainCommandWriter.class);
        when(value.registerCommand(any())).thenAnswer(call -> {
            ChainPersistenceRecords.CommandRecord requested = call.getArgument(0);
            ChainPersistenceRecords.CommandRecord existing = commands.putIfAbsent(
                    requested.commandId(), requested);
            return new ChainPersistenceRecords.AppendResult<>(
                    existing == null ? requested : existing, existing != null);
        });
        when(value.commitCommand(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    String commandId = call.getArgument(0);
                    ChainPersistenceRecords.CommandRecord old = commands.get(commandId);
                    ChainPersistenceRecords.CommandRecord committed =
                            new ChainPersistenceRecords.CommandRecord(
                                    old.commandId(), old.userId(), old.sessionId(),
                                    old.clientRequestId(), old.commandKind(),
                                    old.targetTaskId(), old.targetClientRequestId(),
                                    old.gapId(), old.requestSha256(), old.turnId(),
                                    old.userMessageId(), call.getArgument(1),
                                    call.getArgument(2), call.getArgument(3),
                                    ChainCommandStatus.COMMITTED, null,
                                    old.createdAt(), Instant.now());
                    commands.put(commandId, committed);
                    return committed;
                });
        return value;
    }

    private ChainTaskWriter taskWriter() {
        ChainTaskWriter value = mock(ChainTaskWriter.class);
        when(value.appendTask(any())).thenAnswer(call -> {
            ChainPersistenceRecords.TaskRecord requested = call.getArgument(0);
            ChainPersistenceRecords.TaskRecord existing = tasks.putIfAbsent(
                    requested.taskId(), requested);
            return new ChainPersistenceRecords.AppendResult<>(
                    existing == null ? requested : existing, existing != null);
        });
        return value;
    }

    private ChainInstructionWriter instructionWriter() {
        ChainInstructionWriter value = mock(ChainInstructionWriter.class);
        when(value.appendInstruction(any())).thenAnswer(call -> {
            ChainPersistenceRecords.InstructionRecord requested = call.getArgument(0);
            ChainPersistenceRecords.InstructionRecord existing =
                    instructions.putIfAbsent(requested.instructionId(), requested);
            return new ChainPersistenceRecords.AppendResult<>(
                    existing == null ? requested : existing, existing != null);
        });
        when(value.appendTaskInstructionBinding(any())).thenAnswer(call -> {
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.TaskInstructionBindingRecord> requested =
                    call.getArgument(0);
            var fact = requested.fact();
            var taskBindings = bindings.computeIfAbsent(
                    fact.taskId(), ignored -> new ArrayList<>());
            boolean replayed = taskBindings.stream().anyMatch(existing ->
                    existing.eventId().equals(fact.eventId()));
            if (!replayed) taskBindings.add(fact);
            var taskEvents = events.computeIfAbsent(
                    fact.taskId(), ignored -> new ArrayList<>());
            ChainPersistenceRecords.AuthorityEventRecord event = replayed
                    ? taskEvents.stream().filter(existing -> existing.eventId()
                    .equals(fact.eventId())).findFirst().orElseThrow()
                    : new ChainPersistenceRecords.AuthorityEventRecord(
                    requested.event().eventId(), requested.event().taskId(),
                    taskEvents.size() + 1L, requested.event().eventType(),
                    requested.event().transitionId(),
                    requested.event().sourceIdentitySha256(),
                    requested.event().committedAt());
            if (!replayed) taskEvents.add(event);
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, fact, replayed);
        });
        return value;
    }

    private static void emptyWorkflow(ChainWorkflowRepository workflow) {
        when(workflow.findRouteDecisions(anyString())).thenReturn(List.of());
        when(workflow.findPlanBindings(anyString())).thenReturn(List.of());
        when(workflow.findPendingItems(anyString())).thenReturn(List.of());
        when(workflow.findOpenPendingItems(anyString())).thenReturn(List.of());
        when(workflow.findWorkspaceCandidates(anyString())).thenReturn(List.of());
        when(workflow.findCandidateStepResults(anyString())).thenReturn(List.of());
        when(workflow.findReviewDecisions(anyString())).thenReturn(List.of());
        when(workflow.findAcceptedResults(anyString())).thenReturn(List.of());
    }

    private static void setId(Object target, long id) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
