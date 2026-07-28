package com.yanban.api.agent.v2.progression;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationLeaseDisposition;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicReadyStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.ReadyStepActivationMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCommitted;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionLeaseDisposition;
import io.paperagent.v2.runtime.execution.completion.materialization.DeterministicActiveStepCompletionMaterializer;
import io.paperagent.v2.runtime.execution.progression.StepProgressionInspector;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class EffectDrivenStepProgressionTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-28T04:00:00Z");
    static final PlanStepId A = new PlanStepId("step-a");
    static final PlanStepId B = new PlanStepId("step-b");
    static final ToolCallId TOOL = new ToolCallId("tool-call-a");
    static final EventId ACTIVATION_A = new EventId("activation-a");

    final AgentTurnProductContextResolver contexts =
            mock(AgentTurnProductContextResolver.class);
    final ProductPlanIdDerivation planIds = new ProductPlanIdDerivation();
    final StepProgressionInspector inspector =
            mock(StepProgressionInspector.class);
    final StepRecoverer recoverer = mock(StepRecoverer.class);
    final EffectIntentRepository intents = mock(EffectIntentRepository.class);
    final EffectOutcomeRepository outcomes =
            mock(EffectOutcomeRepository.class);
    final ActiveStepCompletionComposer completion =
            mock(ActiveStepCompletionComposer.class);
    final StepActivationComposer activation =
            mock(StepActivationComposer.class);
    final VerifiedAgentTurnProductContext context =
            new VerifiedAgentTurnProductContext(
                    new AgentRunIdentity(
                            "AGENT_TURN", "42", 7L, 11L, null),
                    Optional.empty());
    final PlanId planId = planIds.derive(context.identity());
    final TaskFrame taskFrame = taskFrame();
    final LeaseRecord lease = new LeaseRecord(
            planId, "owner", "lease-token", 3,
            T0, T0.plusSeconds(300));
    final PersistedStepRecoveryActive activeA = activeA(true);
    final RecoveredActiveStep recoveredA = new RecoveredActiveStep(
            activeA, lease,
            StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    final PersistedEffectIntent intent = new PersistedEffectIntent(
            new EffectIntent(
                    TOOL, planId, A, "literature.search",
                    new ObjectValue(Map.of(
                            "query", new TextValue("graph retrieval")))),
            activeA.activation().leaseOwnerId(),
            activeA.activation().fencingToken(),
            ACTIVATION_A);
    final ExecutionReceipt receipt = new ExecutionReceipt(
            new ReceiptId("receipt-a"), TOOL, ReceiptStatus.SUCCESS,
            T0.plusSeconds(3), T0.plusSeconds(4), Optional.of(0),
            Optional.empty(), OutputCapture.inline("bounded result", false),
            OutputCapture.empty(), List.of(), Optional.empty(), List.of());
    final PersistedEffectResult result = new PersistedEffectResult(
            receipt, intent.leaseOwnerId(), intent.fencingToken());
    final PersistedStepCompletion persistedCompletion =
            persistedCompletion(recoveredA, intent, receipt);
    final PersistedStepRecoveryReady readyB = readyB();
    final PersistedStepRecoveryActive activeB = activeB();
    final AuthenticatedEffectDrivenStepProgressionComposer composer;

    EffectDrivenStepProgressionTestFixtures() {
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(intents.find(TOOL)).thenReturn(PersistenceResult.found(intent));
        when(outcomes.findResult(TOOL))
                .thenReturn(PersistenceResult.found(result));
        when(recoverer.recover(any())).thenReturn(recoveredA);
        when(completion.compose(any())).thenReturn(
                new ActiveStepCompletionCommitted(
                        PersistenceOutcome.APPLIED,
                        persistedCompletion,
                        ActiveStepCompletionLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        when(activation.composeReady(any())).thenReturn(
                new StepActivationCommitted(
                        PersistenceOutcome.APPLIED,
                        activeB.activation(),
                        StepActivationLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        composer = new AuthenticatedEffectDrivenStepProgressionComposer(
                contexts, planIds, inspector, recoverer, intents, outcomes,
                completion, activation);
    }

    EffectDrivenStepProgressionCommand command() {
        return new EffectDrivenStepProgressionCommand(
                planId, TOOL,
                new StepRecoveryLeaseAttempt(
                        lease.ownerId(), lease.leaseToken(),
                        lease.expiresAt()),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        lease.ownerId(), lease.leaseToken(),
                        lease.expiresAt()));
    }

    static DatabaseScenario seedDatabase(
            PlanBootstrapRepository bootstraps,
            LeaseRepository leases,
            ExecutionStartRepository starts,
            StepActivationRepository activations,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes) {
        return seedDatabase(
                bootstraps, leases, starts, activations, intents, outcomes,
                true);
    }

    static DatabaseScenario seedDatabase(
            PlanBootstrapRepository bootstraps,
            LeaseRepository leases,
            ExecutionStartRepository starts,
            StepActivationRepository activations,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            boolean twoSteps) {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        Plan plan = twoSteps
                ? fixture.activeA.plan() : fixture.activeA(false).plan();
        PlanRevision revision = plan.latestRevision();
        Map<PlanStepId, StepExecutionState> initial = new LinkedHashMap<>();
        revision.steps().forEach(step ->
                initial.put(step.id(), StepExecutionState.NOT_STARTED));
        Checkpoint h0 = new Checkpoint(
                fixture.taskFrame.id(), fixture.planId,
                revision.id(), revision.number(), 0,
                PlanExecutionState.NOT_STARTED, initial, List.of(), T0);
        requireApplied(bootstraps.bootstrap(
                fixture.taskFrame, plan, h0));

        String owner = "db-owner";
        String token = "db-token";
        Instant expires = T0.plusSeconds(300);
        LeaseRecord lease = leases.acquire(
                fixture.planId, owner, token, expires)
                .value().orElseThrow();
        EventEnvelope startEvent = new EventEnvelope(
                new EventId("db-start"), fixture.taskFrame.id(),
                fixture.planId, 1, T0.plusSeconds(1),
                new EventType("EXECUTION_STARTED"), Optional.empty(),
                "db-start-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint started = new Checkpoint(
                h0.taskFrameId(), h0.planId(), h0.revisionId(),
                h0.revisionNumber(), 1, PlanExecutionState.ACTIVE,
                h0.stepStates(), h0.receiptReferences(),
                T0.plusSeconds(1));
        requireApplied(starts.start(new ExecutionStartRequest(
                fixture.planId, token, lease.fencingToken(),
                startEvent, started)));

        Map<PlanStepId, StepExecutionState> active =
                new LinkedHashMap<>(started.stepStates());
        active.put(A, StepExecutionState.ACTIVE);
        EventEnvelope activationEvent = new EventEnvelope(
                ACTIVATION_A, fixture.taskFrame.id(), fixture.planId, 2,
                T0.plusSeconds(2), new EventType("STEP_ACTIVATED"),
                Optional.of(startEvent.id()), "db-activation-a",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint activated = new Checkpoint(
                started.taskFrameId(), started.planId(),
                started.revisionId(), started.revisionNumber(), 2,
                PlanExecutionState.ACTIVE, active,
                started.receiptReferences(), T0.plusSeconds(2));
        requireApplied(activations.activate(new StepActivationRequest(
                fixture.planId, token, lease.fencingToken(),
                revision.id(), revision.number(), 2, 1, A,
                activationEvent, activated)));

        requireApplied(intents.persist(new EffectIntentRequest(
                new EffectIntent(
                        TOOL, fixture.planId, A, "literature.search",
                        fixture.intent.intent().arguments()),
                token, lease.fencingToken(), activationEvent.id())));
        requireApplied(outcomes.recordResult(new EffectResultRequest(
                fixture.receipt, token, lease.fencingToken())));
        return new DatabaseScenario(
                fixture,
                new EffectDrivenStepProgressionCommand(
                        fixture.planId, TOOL,
                        new StepRecoveryLeaseAttempt(
                                owner, token, expires),
                        new EffectDrivenStepProgressionActivationLeaseAttempt(
                                owner, token, expires)));
    }

    private static void requireApplied(PersistenceResult<?> result) {
        if (result.outcome() != PersistenceOutcome.APPLIED) {
            throw new AssertionError("database seed was not applied: "
                    + result.outcome());
        }
    }

    record DatabaseScenario(
            EffectDrivenStepProgressionTestFixtures fixture,
            EffectDrivenStepProgressionCommand command) {
    }

    void inspections(StepRecoverySnapshot... snapshots) {
        Queue<StepRecoverySnapshot> cuts =
                new ArrayDeque<>(List.of(snapshots));
        when(inspector.inspect(planId)).thenAnswer(ignored ->
                PersistenceResult.found(cuts.remove()));
    }

    PersistedStepRecoverySucceeded succeeded() {
        PersistedStepRecoveryActive singleActive = activeA(false);
        RecoveredActiveStep recovered = new RecoveredActiveStep(
                singleActive, lease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        PersistedStepCompletion completed =
                persistedCompletion(recovered, intent, receipt);
        return new PersistedStepRecoverySucceeded(
                taskFrame,
                new Plan(
                        planId, taskFrame.id(),
                        List.of(singleActive.plan().latestRevision(),
                                completed.completedRevision())),
                completed.completedCheckpoint(),
                Optional.empty());
    }

    private PersistedStepRecoveryActive activeA(boolean twoSteps) {
        PlanStep a = step(A, Set.of());
        List<PlanStep> steps = twoSteps
                ? List.of(a, step(B, Set.of(A))) : List.of(a);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-1"), taskFrame.id(), 1,
                Optional.empty(), "initial", T0, steps, Map.of());
        Plan plan = new Plan(planId, taskFrame.id(), List.of(revision));
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        states.put(A, StepExecutionState.ACTIVE);
        if (twoSteps) {
            states.put(B, StepExecutionState.NOT_STARTED);
        }
        Checkpoint checkpoint = new Checkpoint(
                taskFrame.id(), planId, revision.id(), revision.number(), 2,
                PlanExecutionState.ACTIVE, states, List.of(),
                T0.plusSeconds(2));
        EventEnvelope event = new EventEnvelope(
                ACTIVATION_A, taskFrame.id(), planId, 2,
                T0.plusSeconds(2), new EventType("step-activation"),
                Optional.empty(), "activation-a-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        VersionedCheckpoint versioned =
                new VersionedCheckpoint(3, checkpoint);
        return new PersistedStepRecoveryActive(
                taskFrame, plan, versioned,
                new PersistedStepActivation(
                        planId, A, "activation-owner", 2,
                        event, versioned),
                Optional.empty());
    }

    private static PersistedStepCompletion persistedCompletion(
            RecoveredActiveStep recovered,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt) {
        StepCompletionRequest request =
                new DeterministicActiveStepCompletionMaterializer()
                        .materialize(
                                EffectDrivenStepProgressionDrafts.completion(
                                        recovered, intent, receipt));
        return new PersistedStepCompletion(
                request.planId(), request.stepId(),
                recovered.lease().ownerId(),
                recovered.lease().fencingToken(),
                request.completionEvent(),
                request.completedRevision(),
                new VersionedCheckpoint(
                        request.expectedCheckpointVersion() + 1,
                        request.completedCheckpoint()));
    }

    private PersistedStepRecoveryReady readyB() {
        return new PersistedStepRecoveryReady(
                taskFrame,
                new Plan(
                        planId, taskFrame.id(),
                        List.of(activeA.plan().latestRevision(),
                                persistedCompletion.completedRevision())),
                persistedCompletion.completedCheckpoint(), B,
                Optional.empty());
    }

    private PersistedStepRecoveryActive activeB() {
        var attempt = EffectDrivenStepProgressionDrafts.activation(
                readyB, intent, receipt,
                command().nextStepActivationAttempt());
        var materialized = new DeterministicReadyStepActivationMaterializer()
                .materialize(new ReadyStepActivationMaterializationRequest(
                        readyB, attempt.eventDraft(),
                        attempt.checkpointCreatedAt()));
        VersionedCheckpoint versioned = new VersionedCheckpoint(
                readyB.checkpoint().version() + 1,
                materialized.activatedCheckpoint());
        PersistedStepActivation persisted = new PersistedStepActivation(
                planId, B, lease.ownerId(), lease.fencingToken(),
                materialized.activationEvent(), versioned);
        return new PersistedStepRecoveryActive(
                taskFrame, readyB.plan(), versioned, persisted,
                Optional.empty());
    }

    private TaskFrame taskFrame() {
        return new TaskFrame(
                new TaskFrameId("task-frame"),
                "progress successful effect",
                List.of("project"),
                List.of("completed step"),
                List.of("persisted authority only"),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD, Set.of(),
                        NetworkPolicy.DENY_ALL, List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(1),
                                1024, 1024, 1),
                        Set.of()),
                T0);
    }

    private static PlanStep step(
            PlanStepId id, Set<PlanStepId> dependencies) {
        return new PlanStep(
                id, "execute " + id.value(), "complete " + id.value(),
                dependencies, List.of("receipt"),
                new BoundedExecutionHints(
                        1, Duration.ofMinutes(1)));
    }
}
