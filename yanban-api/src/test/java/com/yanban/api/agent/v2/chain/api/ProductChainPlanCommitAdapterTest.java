package com.yanban.api.agent.v2.chain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductActiveStepReplanCodec;
import com.yanban.api.agent.v2.persistence.ProductActiveStepReplanRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanCodec;
import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.route.ChainPlanCommitPort;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanReplanRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.runtime.bootstrap.DefaultPersistentPlanBootstrapper;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.checkpoint.DeterministicInitialCheckpointFreezer;
import io.paperagent.v2.runtime.planning.DeterministicInitialPlanFreezer;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ProductChainPlanCommitAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-07T01:02:03Z");
    private static final String HASH = "a".repeat(64);
    private final ChainFoundationRepository foundations =
            mock(ChainFoundationRepository.class);
    private final AgentTurnProductContextResolver contexts =
            mock(AgentTurnProductContextResolver.class);
    private final PersistentPlanBootstrapper bootstraps =
            mock(PersistentPlanBootstrapper.class);
    private final ProductPlanBootstrapCodec codec =
            new ProductPlanBootstrapCodec(new ObjectMapper());
    private final StepRecoveryRepository recoveries =
            mock(StepRecoveryRepository.class);
    private final LeaseRepository leases = mock(LeaseRepository.class);
    private final PlanReplanRepository replans =
            mock(PlanReplanRepository.class);
    private final ProductPlanReplanMarkerReader replanMarkers =
            mock(ProductPlanReplanMarkerReader.class);
    private final ProductActiveStepReplanRepositoryAdapter activeReplans =
            mock(ProductActiveStepReplanRepositoryAdapter.class);
    private ProductChainPlanCommitAdapter adapter;

    @BeforeEach
    void setUp() {
        when(activeReplans.findCommitted(any()))
                .thenReturn(Optional.empty());
        when(replanMarkers.findAllByPlanId(any()))
                .thenReturn(List.of());
        when(foundations.findTask("task-1")).thenReturn(Optional.of(task()));
        when(foundations.findInstruction("instruction-1"))
                .thenReturn(Optional.of(instruction()));
        when(contexts.resolve(7L, 101L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "101", 7L, 11L, 31L),
                        Optional.of("a-newer-project-version")));
        adapter = new ProductChainPlanCommitAdapter(
                foundations, contexts,
                new ProductPersistentPlanBootstrapRequestAdapter(),
                bootstraps, codec, recoveries, leases, replans,
                replanMarkers, new ProductPlanReplanCodec(
                new ObjectMapper()), activeReplans,
                new ProductActiveStepReplanCodec(new ObjectMapper()));
    }

    @Test
    void commitsAppliedAndReplayedBootstrapWithExactCodecAuthority() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        var delegate = new DefaultPersistentPlanBootstrapper(
                new DeterministicTaskFrameFreezer(),
                new DeterministicInitialPlanFreezer(),
                new DeterministicInitialCheckpointFreezer(),
                persistence.planBootstraps());
        AtomicReference<io.paperagent.v2.persistence.PersistedPlanBootstrap>
                committed = new AtomicReference<>();
        when(bootstraps.bootstrap(any())).thenAnswer(call -> {
            var result = delegate.bootstrap(call.getArgument(0));
            committed.set(result.value().orElseThrow());
            return result;
        });

        ChainPlanCommitPort.CommittedPlan applied = adapter
                .commitPersistent(command(payload("SANDBOX_STANDARD",
                        "project-version-1")));
        ChainPlanCommitPort.CommittedPlan replayed = adapter
                .commitPersistent(command(payload("SANDBOX_STANDARD",
                        "project-version-1")));

        assertEquals(applied, replayed);
        assertEquals("task-1", applied.taskId());
        assertEquals(1, applied.planRevisionNumber());
        assertEquals("STABLE_V2_PLAN", applied.authorityType());
        assertEquals(applied.planRevisionId(), applied.authorityId());
        assertEquals(codec.encode(committed.get()).sha256(),
                applied.authoritySha256());
        var encoded = codec.encode(committed.get());
        assertEquals(committed.get(), codec.decode(
                encoded.formatVersion(), encoded.sha256(), encoded.json()));

        ArgumentCaptor<PersistentPlanBootstrapRequest> captured =
                ArgumentCaptor.forClass(PersistentPlanBootstrapRequest.class);
        verify(bootstraps, org.mockito.Mockito.times(2)).bootstrap(
                captured.capture());
        var mapped = captured.getAllValues().get(0);
        assertEquals(NOW, mapped.taskFrameFreezeRequest().createdAt());
        assertEquals("Compile and improve Sort.java",
                mapped.taskFrameFreezeRequest().draft().objective());
        assertTrue(mapped.taskFrameFreezeRequest().executionProfile()
                .capabilities().contains(
                io.paperagent.v2.contracts.Capability.EXECUTE_COMMAND));
        assertEquals(io.paperagent.v2.contracts.NetworkPolicy.DENY_ALL,
                mapped.taskFrameFreezeRequest().executionProfile()
                        .networkPolicy());
        assertEquals("project-version-1", mapped.taskFrameFreezeRequest()
                .sourceProjectVersion().orElseThrow().versionId());
        assertEquals(Set.of(
                        RoutingRequirement.PROJECT_FILE_ACCESS,
                        RoutingRequirement.TOOL_USE,
                        RoutingRequirement.MODIFICATION),
                mapped.taskFrameFreezeRequest().routingDecision()
                        .requirements());
        assertEquals("merge-sort", mapped.initialPlanDraft().steps().get(0)
                .id().value());
        assertEquals(List.of("Step constraint: preserve existing sorting implementations"),
                mapped.initialPlanDraft().steps().get(1).completionCriteria().stream()
                        .filter(value -> value.startsWith("Step constraint:"))
                        .toList());
        assertEquals(List.of("preserve existing sorting implementations"),
                mapped.initialPlanDraft().steps().get(1).constraints());
        assertTrue(mapped.initialPlanDraft().steps().get(0).mayChangeCandidate());
        assertNull(mapped.initialPlanDraft().steps().get(0)
                .candidateValidationCompletionCondition());
        assertFalse(mapped.initialPlanDraft().steps().get(1).mayChangeCandidate());
        assertEquals("merge sort output is shown",
                mapped.initialPlanDraft().steps().get(1)
                        .candidateValidationCompletionCondition());
    }

    @Test
    void rejectsUnknownPermissionTierBeforeBootstrap() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> adapter.commitPersistent(
                        command(payload("ADMIN", "project-version-1"))));

        assertEquals("CHAIN_PLAN_PERMISSION_TIER_UNSUPPORTED",
                failure.getMessage());
        verify(bootstraps, never()).bootstrap(any());
    }

    @Test
    void rejectsProjectVersionDriftBeforeBootstrap() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> adapter.commitPersistent(
                        command(payload("SANDBOX_STANDARD", "project-version-2"))));

        assertEquals("CHAIN_PLAN_PROJECT_VERSION_MISMATCH",
                failure.getMessage());
        verify(bootstraps, never()).bootstrap(any());
    }

    @Test
    void rejectsBootstrapFailureAndNullRevisionCommand() {
        when(bootstraps.bootstrap(any())).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY, "plan.id"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> adapter.commitPersistent(
                        command(payload("SANDBOX_STANDARD",
                                "project-version-1"))));
        assertEquals("CHAIN_PLAN_BOOTSTRAP_REJECTED", failure.getMessage());
        NullPointerException revision = assertThrows(
                NullPointerException.class,
                () -> adapter.commitRevision(null));
        assertEquals("command", revision.getMessage());
    }

    @Test
    void activeRevisionCreatesOnceAndRecoversByExactFormalMarker() {
        var fixture = activeFixture();
        AtomicReference<PersistedActiveStepReplan> committed =
                new AtomicReference<>();
        when(activeReplans.findCommitted(any())).thenAnswer(call ->
                Optional.ofNullable(committed.get()));
        when(activeReplans.supersedeAndReplan(any())).thenAnswer(call -> {
            io.paperagent.v2.persistence.ActiveStepReplanRequest request =
                    call.getArgument(0);
            PersistedActiveStepReplan result =
                    new PersistedActiveStepReplan(
                            request.planId(), request.activeStepId(),
                            "owner-1", request.fencingToken(),
                            request.supersessionEvent(),
                            new VersionedCheckpoint(
                                    request.expectedCheckpointVersion() + 1,
                                    request.supersededCheckpoint()),
                            request.replanEvent(),
                            request.replannedRevision(),
                            new VersionedCheckpoint(
                                    request.expectedCheckpointVersion() + 2,
                                    request.replannedCheckpoint()));
            committed.set(result);
            return PersistenceResult.applied(result);
        });

        var first = adapter.commitRevision(fixture.command());
        var replay = adapter.commitRevision(fixture.command());

        assertEquals(first, replay);
        assertEquals(2L, first.planRevisionNumber());
        assertEquals("STABLE_V2_PLAN", first.authorityType());
        verify(activeReplans, times(1)).supersedeAndReplan(any());
        var stored = committed.get();
        assertEquals("transition-1",
                stored.supersessionEvent().correlationId());
        assertEquals(StepExecutionState.SUPERSEDED_BY_REPLAN,
                stored.supersededCheckpoint().checkpoint().stepStates()
                        .get(new PlanStepId("old-step")));
    }

    @Test
    void activeRevisionRejectsAConflictingReplayPayload() {
        var fixture = activeFixture();
        AtomicReference<PersistedActiveStepReplan> committed =
                new AtomicReference<>();
        when(activeReplans.findCommitted(any())).thenAnswer(call ->
                Optional.ofNullable(committed.get()));
        when(activeReplans.supersedeAndReplan(any())).thenAnswer(call -> {
            io.paperagent.v2.persistence.ActiveStepReplanRequest request =
                    call.getArgument(0);
            var result = new PersistedActiveStepReplan(
                    request.planId(), request.activeStepId(), "owner-1",
                    request.fencingToken(), request.supersessionEvent(),
                    new VersionedCheckpoint(
                            request.expectedCheckpointVersion() + 1,
                            request.supersededCheckpoint()),
                    request.replanEvent(), request.replannedRevision(),
                    new VersionedCheckpoint(
                            request.expectedCheckpointVersion() + 2,
                            request.replannedCheckpoint()));
            committed.set(result);
            return PersistenceResult.applied(result);
        });
        adapter.commitRevision(fixture.command());
        var conflicting = revisionCommand(revisionPayload("other-step"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.commitRevision(conflicting));

        assertEquals("CHAIN_PLAN_REVISION_REPLAY_MISMATCH",
                failure.getMessage());
        verify(activeReplans, times(1)).supersedeAndReplan(any());
    }

    @Test
    void revisionDraftMustReplaceTheSupersededActiveStepIdentity() {
        activeFixture();
        var currentPlan = new ChainPersistenceRecords.PlanBindingRecord(
                "binding-1", "task-1", "binding-event-1",
                "instruction-1", "route-1", "frame-1", "plan-1",
                "revision-1", 1L, "STABLE_V2_PLAN", "revision-1",
                HASH, "transition-1", NOW);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> adapter.validateActiveStepReplacementIdentity(
                        currentPlan, revisionPayload("old-step")));

        assertTrue(failure.getMessage().contains(
                "must not reuse active stepKey old-step"));
        adapter.validateActiveStepReplacementIdentity(
                currentPlan, revisionPayload("replacement-step"));
    }

    @Test
    void validatesRevisionAgainstCompletionOnlyDescendantOfBoundPlan() {
        var fixture = completionDescendantFixture();
        var currentPlan = fixture.currentPlan();
        var valid = fixture.payload();

        adapter.validateActiveStepReplacementIdentity(currentPlan, valid);

        PlannerPayload.PlanRevision invalid = new PlannerPayload.PlanRevision(
                "review-1", "revision-1",
                new ProposalFields.PlanDraft(List.of(
                        stepDraft("repair-step", 1, List.of()),
                        stepDraft("completed-step", 2,
                                List.of("repair-step")),
                        stepDraft("active-step", 3,
                                List.of("completed-step")))),
                valid.requirementCoverage(), valid.applicability(),
                valid.unmetRequirements(), valid.assumptions(), valid.risks(),
                "frame-1", null);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> adapter.validateActiveStepReplacementIdentity(
                        currentPlan, invalid));
        assertTrue(failure.getMessage().contains(
                "must replace the superseded active Step"));
        assertTrue(failure.getMessage().contains(
                "must preserve completed Step completed-step"));
    }

    @Test
    void commitsRevisionFromCompletionOnlyDescendantOfBoundPlan() {
        var fixture = completionDescendantFixture();
        AtomicReference<PersistedActiveStepReplan> committed =
                new AtomicReference<>();
        when(activeReplans.findCommitted(any())).thenAnswer(call ->
                Optional.ofNullable(committed.get()));
        when(activeReplans.supersedeAndReplan(any())).thenAnswer(call -> {
            io.paperagent.v2.persistence.ActiveStepReplanRequest request =
                    call.getArgument(0);
            var result = new PersistedActiveStepReplan(
                    request.planId(), request.activeStepId(), "owner-1",
                    request.fencingToken(), request.supersessionEvent(),
                    new VersionedCheckpoint(
                            request.expectedCheckpointVersion() + 1,
                            request.supersededCheckpoint()),
                    request.replanEvent(), request.replannedRevision(),
                    new VersionedCheckpoint(
                            request.expectedCheckpointVersion() + 2,
                            request.replannedCheckpoint()));
            committed.set(result);
            return PersistenceResult.applied(result);
        });

        var result = adapter.commitRevision(
                revisionCommand(fixture.payload()));

        assertEquals(3L, result.planRevisionNumber());
        assertEquals("completion-2", committed.get().replannedRevision()
                .parentRevisionId().orElseThrow().value());
        assertTrue(committed.get().replannedRevision().completedFacts()
                .containsKey(new PlanStepId("completed-step")));
    }

    private CompletionDescendantFixture completionDescendantFixture() {
        TaskFrameId frameId = new TaskFrameId("frame-1");
        PlanId planId = new PlanId("plan-1");
        PlanStep completedStep = step("completed-step", Set.of());
        PlanStep activeStep = step("active-step",
                Set.of(completedStep.id()));
        PlanRevision bound = new PlanRevision(
                new PlanRevisionId("revision-1"), frameId, 1L,
                Optional.empty(), "initial", NOW,
                List.of(completedStep, activeStep), Map.of());
        CompletionFact fact = new CompletionFact(
                completedStep.id(), "completed", NOW.plusMillis(1),
                List.of(new ReceiptId("receipt-1")));
        PlanRevision completion = new PlanRevision(
                new PlanRevisionId("completion-2"), frameId, 2L,
                Optional.of(bound.id()), "completed first step",
                NOW.plusMillis(1), bound.steps(),
                Map.of(completedStep.id(), fact));
        Plan plan = new Plan(planId, frameId,
                List.of(bound, completion));
        Checkpoint current = new Checkpoint(frameId, planId,
                completion.id(), 2L, 4L, PlanExecutionState.ACTIVE,
                Map.of(completedStep.id(), StepExecutionState.SUCCEEDED,
                        activeStep.id(), StepExecutionState.ACTIVE),
                List.of(), NOW.plusMillis(2));
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-1"), frameId, planId, 4L,
                NOW.plusMillis(2), new EventType("STEP_ACTIVATED"),
                Optional.empty(), "activation-1",
                new InlineEventPayload(new ObjectValue(Map.of())));
        PersistedStepActivation activation = new PersistedStepActivation(
                planId, activeStep.id(), "owner-1", 7L,
                activationEvent, new VersionedCheckpoint(5L, current));
        TaskFrame frame = mock(TaskFrame.class);
        when(frame.id()).thenReturn(frameId);
        when(frame.requirements()).thenReturn(
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(), io.paperagent.v2.contracts
                                .PublishRequirement.NOT_REQUIRED));
        when(recoveries.inspect(planId)).thenReturn(PersistenceResult.found(
                new PersistedStepRecoveryActive(frame, plan,
                        new VersionedCheckpoint(5L, current), activation,
                        Optional.empty())));
        when(leases.find(planId)).thenReturn(PersistenceResult.found(
                new LeaseRecord(planId, "owner-1", "lease-1", 7L,
                        NOW, NOW.plusSeconds(60))));
        var currentPlan = new ChainPersistenceRecords.PlanBindingRecord(
                "binding-1", "task-1", "binding-event-1",
                "instruction-1", "route-1", "frame-1", "plan-1",
                "revision-1", 1L, "STABLE_V2_PLAN", "revision-1",
                HASH, "transition-1", NOW);
        PlannerPayload.PlanRevision valid = completionRevisionPayload(
                completedStep, "replacement-step");
        return new CompletionDescendantFixture(currentPlan, valid);
    }

    private static ChainPlanCommitPort.PersistentPlanCommand command(
            PlannerPayload.PersistentPlan payload) {
        return new ChainPlanCommitPort.PersistentPlanCommand(
                "task-1", "instruction-1", "proposal-1", "route-1",
                "transition-1", NOW, payload);
    }

    private ActiveFixture activeFixture() {
        TaskFrameId frameId = new TaskFrameId("frame-1");
        PlanId planId = new PlanId("plan-1");
        PlanStepId oldStepId = new PlanStepId("old-step");
        PlanStep oldStep = new PlanStep(oldStepId, "old objective",
                "old complete", Set.of(), List.of("receipt"),
                new BoundedExecutionHints(1,
                        java.time.Duration.ofMinutes(1)));
        PlanRevision previous = new PlanRevision(
                new PlanRevisionId("revision-1"), frameId, 1L,
                Optional.empty(), "initial", NOW, List.of(oldStep),
                Map.of());
        Plan plan = new Plan(planId, frameId, List.of(previous));
        Checkpoint activated = new Checkpoint(frameId, planId,
                previous.id(), 1L, 2L, PlanExecutionState.ACTIVE,
                Map.of(oldStepId, StepExecutionState.ACTIVE), List.of(),
                NOW.plusMillis(2));
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-1"), frameId, planId, 2L,
                NOW.plusMillis(2), new EventType("STEP_ACTIVATED"),
                Optional.empty(), "activation-1",
                new InlineEventPayload(new ObjectValue(Map.of())));
        PersistedStepActivation activation =
                new PersistedStepActivation(planId, oldStepId,
                        "owner-1", 7L, activationEvent,
                        new VersionedCheckpoint(3L, activated));
        Checkpoint current = new Checkpoint(frameId, planId,
                previous.id(), 1L, 4L, PlanExecutionState.ACTIVE,
                Map.of(oldStepId, StepExecutionState.ACTIVE), List.of(),
                NOW.plusMillis(4));
        TaskFrame frame = mock(TaskFrame.class);
        when(frame.id()).thenReturn(frameId);
        when(frame.requirements()).thenReturn(
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(),
                        io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        PersistedStepRecoveryActive active =
                new PersistedStepRecoveryActive(frame, plan,
                        new VersionedCheckpoint(5L, current), activation,
                        Optional.empty());
        when(recoveries.inspect(planId))
                .thenReturn(PersistenceResult.found(active));
        when(leases.find(planId)).thenReturn(PersistenceResult.found(
                new LeaseRecord(planId, "owner-1", "lease-1", 7L,
                        NOW, NOW.plusSeconds(60))));
        return new ActiveFixture(revisionCommand(
                revisionPayload("replacement-step")));
    }

    private static ChainPlanCommitPort.PlanRevisionCommand revisionCommand(
            PlannerPayload.PlanRevision payload) {
        return new ChainPlanCommitPort.PlanRevisionCommand(
                "task-1", "instruction-1", "proposal-revision-1",
                "REVIEW_DECISION", "review-1", "transition-1",
                "frame-1", "plan-1", "revision-1", 1L, NOW, payload);
    }

    private static PlannerPayload.PlanRevision revisionPayload(
            String stepKey) {
        var step = new ProposalFields.StepDraft(
                stepKey, 1, "replacement objective", List.of(),
                List.of("replacement complete"), List.of("target"),
                List.of("receipt"), false, null);
        return new PlannerPayload.PlanRevision(
                "review-1", "revision-1",
                new ProposalFields.PlanDraft(List.of(step)),
                List.of(new ProposalFields.RequirementCoverage(
                        "requirement", ProposalFields.RequirementStatus.PLANNED,
                        List.of())), List.of(), List.of(), List.of(),
                List.of(), "frame-1", null);
    }

    private static PlannerPayload.PlanRevision completionRevisionPayload(
            PlanStep completedStep, String replacementStepKey) {
        return new PlannerPayload.PlanRevision(
                "review-1", "revision-1",
                new ProposalFields.PlanDraft(List.of(
                        stepDraft(completedStep, 1),
                        stepDraft(replacementStepKey, 2,
                                List.of("completed-step")))),
                List.of(new ProposalFields.RequirementCoverage(
                        "requirement", ProposalFields.RequirementStatus.PLANNED,
                        List.of())), List.of(), List.of(), List.of(), List.of(),
                "frame-1", null);
    }

    private static PlanStep step(
            String id, Set<PlanStepId> dependencies) {
        return new PlanStep(new PlanStepId(id), "objective " + id,
                "complete " + id, dependencies,
                List.of("condition " + id,
                        "Allowed scope: Sort.java",
                        "Candidate modification: forbidden"),
                new BoundedExecutionHints(8,
                        java.time.Duration.ofMinutes(10)),
                List.of(), false, null, List.of());
    }

    private static ProposalFields.StepDraft stepDraft(
            PlanStep step, int order) {
        return new ProposalFields.StepDraft(
                step.id().value(), order, step.intent(),
                step.dependencies().stream().map(PlanStepId::value).toList(),
                List.of("condition " + step.id().value()),
                List.of("Sort.java"), List.of(step.expectedOutcome()),
                step.mayChangeCandidate(),
                step.candidateValidationCompletionCondition(),
                step.constraints(), step.validationRequirementIds());
    }

    private static ProposalFields.StepDraft stepDraft(
            String id, int order, List<String> dependencies) {
        return new ProposalFields.StepDraft(
                id, order, "objective " + id, dependencies,
                List.of("condition " + id), List.of("Sort.java"),
                List.of("complete " + id), false, null,
                List.of(), List.of());
    }

    private record ActiveFixture(
            ChainPlanCommitPort.PlanRevisionCommand command) {
    }

    private record CompletionDescendantFixture(
            ChainPersistenceRecords.PlanBindingRecord currentPlan,
            PlannerPayload.PlanRevision payload) {
    }

    private static PlannerPayload.PersistentPlan payload(
            String permissionTier, String projectVersion) {
        var frame = new ProposalFields.TaskFrameDraft(
                "Compile and improve Sort.java", List.of("Sort.java"),
                List.of("compiled output", "merge-sort output"),
                List.of("work only in the sandbox"), projectVersion,
                permissionTier,
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(new io.paperagent.v2.contracts.ValidationRequirement(
                                "candidate-validation",
                                io.paperagent.v2.contracts.ValidationSubject.CANDIDATE,
                                "merge sort output is shown")),
                        io.paperagent.v2.contracts.PublishRequirement.REQUIRED));
        var first = new ProposalFields.StepDraft(
                "merge-sort", 1, "Add merge sort", List.of(),
                List.of("merge sort is implemented"),
                List.of("Sort.java"), List.of("updated Sort.java"),
                true, null);
        var second = new ProposalFields.StepDraft(
                "validate", 2, "Compile and run Sort.java",
                List.of("merge-sort"),
                List.of("Sort.java compiles", "Sort.java runs",
                        "merge sort output is shown"),
                List.of("Sort.java"), List.of("execution receipt"),
                false, "merge sort output is shown",
                List.of("preserve existing sorting implementations"),
                List.of("candidate-validation"));
        return new PlannerPayload.PersistentPlan(
                frame,
                new ProposalFields.RoutingBoundary(
                        true, false, true, false),
                List.of(new ProposalFields.RequirementCoverage(
                        "compile and run", ProposalFields.RequirementStatus.PLANNED,
                        List.of())),
                new ProposalFields.PlanDraft(List.of(first, second)),
                List.of(), null);
    }

    @Test
    void acceptsCandidateValidationOnLaterDependentStep() {
        var frame = new ProposalFields.TaskFrameDraft(
                "Modify and verify Sort.java", List.of("Sort.java"),
                List.of("verified Sort.java"), List.of(),
                "project-version-1", "SANDBOX_STANDARD",
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(new io.paperagent.v2.contracts
                                .ValidationRequirement(
                                "candidate-validation",
                                io.paperagent.v2.contracts.ValidationSubject
                                        .CANDIDATE,
                                "the exact candidate passes validation")),
                        io.paperagent.v2.contracts.PublishRequirement.REQUIRED));
        var change = new ProposalFields.StepDraft(
                "change", 1, "Modify Sort.java", List.of(),
                List.of("candidate created"), List.of("Sort.java"),
                List.of("candidate"), true, null, List.of(), List.of());
        var validate = new ProposalFields.StepDraft(
                "validate", 2, "Validate the candidate", List.of("change"),
                List.of("the exact candidate passes validation"),
                List.of("Sort.java"), List.of("validation receipt"), false,
                "the exact candidate passes validation", List.of(),
                List.of("candidate-validation"));

        PlannerPayload.PersistentPlan plan = new PlannerPayload.PersistentPlan(
                frame, new ProposalFields.RoutingBoundary(
                true, false, true, false),
                List.of(new ProposalFields.RequirementCoverage(
                        "verified Sort.java",
                        ProposalFields.RequirementStatus.PLANNED, List.of())),
                new ProposalFields.PlanDraft(List.of(change, validate)),
                List.of(), null);

        assertEquals(List.of("candidate-validation"),
                plan.initialPlan().steps().get(1)
                        .validationRequirementIds());
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                7L, 11L, 101L, 201L, "client-1", HASH,
                31L, "project-version-1", 0L, NOW.minusSeconds(1));
    }

    private static ChainPersistenceRecords.InstructionRecord instruction() {
        return new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-1", 11L, "task-1", 201L,
                HASH, "command:command-1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
    }
}
