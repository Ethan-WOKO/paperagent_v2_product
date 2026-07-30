package com.yanban.api.agent.v2.loop;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionComposer;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionOutcome;
import com.yanban.api.agent.v2.progression.AuthenticatedEffectDrivenStepProgressionComposer;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionActivationLeaseAttempt;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionOutcome;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionState;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnIntentPersisted;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanComposer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PersistentPlanAgentLoopTestSupport {
    static final long USER_ID = 7L;
    static final long TURN_ID = 42L;
    static final Instant NOW = Instant.parse("2099-07-28T00:00:00Z");

    private PersistentPlanAgentLoopTestSupport() {
    }

    static PersistentPlanAgentLoopCommand command(int cycles) {
        return new PersistentPlanAgentLoopCommand(
                cycles,
                new StepRecoveryLeaseAttempt(
                        "owner", "lease-token",
                        NOW.plusSeconds(60)),
                new StepActivationAttempt(
                        "owner", "lease-token",
                        NOW.plusSeconds(60),
                        new StepActivationEventDraft(
                                new EventId("ready-activation"),
                                NOW, new EventType("STEP_ACTIVATED"),
                                Optional.empty(), "loop-test",
                                new InlineEventPayload(
                                        new ObjectValue(Map.of()))),
                        NOW),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        "owner", "lease-token",
                        NOW.plusSeconds(60)),
                Optional.empty());
    }

    static PersistentPlanAgentLoopCommand command(
            int cycles, ActiveStepReplanRequest proposal) {
        PersistentPlanAgentLoopCommand plain = command(cycles);
        return new PersistentPlanAgentLoopCommand(
                plain.maxCycles(),
                plain.currentRecoveryAttempt(),
                plain.readyActivationAttempt(),
                plain.nextStepActivationAttempt(),
                Optional.of(proposal));
    }

    static PersistentPlanAgentLoopCommand command(
            int cycles, LeaseRecord lease) {
        return new PersistentPlanAgentLoopCommand(
                cycles,
                new StepRecoveryLeaseAttempt(
                        lease.ownerId(), lease.leaseToken(),
                        lease.expiresAt()),
                new StepActivationAttempt(
                        lease.ownerId(), lease.leaseToken(),
                        lease.expiresAt(),
                        new StepActivationEventDraft(
                                new EventId("loop-ready-activation"),
                                NOW, new EventType("STEP_ACTIVATED"),
                                Optional.empty(), "loop-durable",
                                new InlineEventPayload(
                                        new ObjectValue(Map.of()))),
                        NOW),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        lease.ownerId(), lease.leaseToken(),
                        lease.expiresAt()),
                Optional.empty());
    }

    static LoopFixture fixture() {
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        ProductPlanIdDerivation planIds = new ProductPlanIdDerivation();
        VerifiedAgentTurnProductContext context =
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "turn-42",
                                USER_ID, 11L, null),
                        Optional.empty());
        when(contexts.resolve(USER_ID, TURN_ID)).thenReturn(context);
        PlanId planId = planIds.derive(context.identity());
        StepRecoveryRepository inspections =
                mock(StepRecoveryRepository.class);
        StepRecoverer recoverer = mock(StepRecoverer.class);
        StepActivationComposer activation =
                mock(StepActivationComposer.class);
        SingleTurnStepKernel kernel =
                mock(SingleTurnStepKernel.class);
        AuthenticatedLiteratureSearchEffectExecutionComposer effects =
                mock(AuthenticatedLiteratureSearchEffectExecutionComposer.class);
        var projectEffects = mock(com.yanban.api.agent.v2.effect.project
                .AuthenticatedProjectEffectExecutionComposer.class);
        AuthenticatedEffectDrivenStepProgressionComposer progression =
                mock(AuthenticatedEffectDrivenStepProgressionComposer.class);
        BoundedStepReplanComposer replans =
                mock(BoundedStepReplanComposer.class);
        AuthenticatedPersistentPlanAgentLoopComposer composer =
                new AuthenticatedPersistentPlanAgentLoopComposer(
                        contexts, planIds, inspections, recoverer,
                        activation, kernel, effects, projectEffects, progression,
                        replans);
        return new LoopFixture(
                planId, contexts, inspections, recoverer, activation,
                kernel, effects, projectEffects, progression, replans,
                composer);
    }

    static ActiveCut active(PlanId planId, String step) {
        PlanStepId stepId = new PlanStepId(step);
        PersistedStepActivation activation =
                mock(PersistedStepActivation.class);
        when(activation.stepId()).thenReturn(stepId);
        PersistedStepRecoveryActive recovery =
                mock(PersistedStepRecoveryActive.class);
        when(recovery.planId()).thenReturn(planId);
        when(recovery.activation()).thenReturn(activation);
        RecoveredActiveStep active = mock(RecoveredActiveStep.class);
        when(active.planId()).thenReturn(planId);
        when(active.recovery()).thenReturn(recovery);
        return new ActiveCut(stepId, recovery, active);
    }

    static IntentCut intent(
            PlanId planId, PlanStepId stepId, String suffix,
            String kind) {
        ToolCallId toolCallId = new ToolCallId("tool-" + suffix);
        EffectIntent intent = new EffectIntent(
                toolCallId, planId, stepId, kind,
                new ObjectValue(Map.of()));
        PersistedEffectIntent persisted = new PersistedEffectIntent(
                intent, "owner", 1L,
                new EventId("activation-" + suffix));
        return new IntentCut(
                toolCallId, persisted,
                new SingleTurnIntentPersisted(persisted));
    }

    static AuthenticatedLiteratureSearchEffectExecutionOutcome
            successfulEffect(ToolCallId toolCallId) {
        var receipt = mock(io.paperagent.v2.contracts.ExecutionReceipt.class);
        when(receipt.toolCallId()).thenReturn(toolCallId);
        when(receipt.status()).thenReturn(
                io.paperagent.v2.contracts.ReceiptStatus.SUCCESS);
        PersistedEffectResult result = mock(PersistedEffectResult.class);
        when(result.receipt()).thenReturn(receipt);
        return new AuthenticatedLiteratureSearchEffectExecutionOutcome(
                result, false);
    }

    static com.yanban.api.agent.v2.effect.project
            .AuthenticatedProjectEffectExecutionOutcome
            successfulProjectEffect(ToolCallId toolCallId) {
        var receipt = mock(io.paperagent.v2.contracts.ExecutionReceipt.class);
        when(receipt.toolCallId()).thenReturn(toolCallId);
        when(receipt.status()).thenReturn(
                io.paperagent.v2.contracts.ReceiptStatus.SUCCESS);
        PersistedEffectResult result = mock(PersistedEffectResult.class);
        when(result.receipt()).thenReturn(receipt);
        return new com.yanban.api.agent.v2.effect.project
                .AuthenticatedProjectEffectExecutionOutcome(result, false);
    }

    static EffectDrivenStepProgressionOutcome progression(
            PlanId planId, PlanStepId completedStepId,
            EffectDrivenStepProgressionState state,
            io.paperagent.v2.persistence.StepRecoverySnapshot snapshot) {
        EffectDrivenStepProgressionOutcome outcome =
                mock(EffectDrivenStepProgressionOutcome.class);
        when(outcome.planId()).thenReturn(planId);
        when(outcome.completedStepId()).thenReturn(completedStepId);
        when(outcome.state()).thenReturn(state);
        when(outcome.snapshot()).thenReturn(snapshot);
        return outcome;
    }

    static DurableScenario seedDurableTwoStep(
            PlanId planId,
            PlanBootstrapRepository bootstraps,
            LeaseRepository leases,
            ExecutionStartRepository starts,
            StepActivationRepository activations) {
        Instant createdAt = Instant.now()
                .truncatedTo(ChronoUnit.MICROS)
                .minusSeconds(30);
        TaskFrame taskFrame = durableTaskFrame(createdAt);
        PlanStepId a = new PlanStepId("step-a");
        PlanStepId b = new PlanStepId("step-b");
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-1"),
                taskFrame.id(), 1, Optional.empty(),
                "durable two-step loop", createdAt,
                List.of(
                        durableStep(a, Set.of()),
                        durableStep(b, Set.of(a))),
                Map.of());
        Plan plan = new Plan(
                planId, taskFrame.id(), List.of(revision));
        Map<PlanStepId, StepExecutionState> initial =
                new LinkedHashMap<>();
        initial.put(a, StepExecutionState.NOT_STARTED);
        initial.put(b, StepExecutionState.NOT_STARTED);
        Checkpoint h0 = new Checkpoint(
                taskFrame.id(), planId, revision.id(),
                revision.number(), 0,
                PlanExecutionState.NOT_STARTED,
                initial, List.of(), createdAt);
        requireApplied(bootstraps.bootstrap(
                taskFrame, plan, h0));

        String owner = "durable-owner";
        String token = "durable-token";
        LeaseRecord lease = leases.acquire(
                        planId, owner, token,
                        Instant.now().plus(Duration.ofMinutes(10)))
                .value().orElseThrow();
        EventEnvelope startEvent = new EventEnvelope(
                new EventId("durable-start"),
                taskFrame.id(), planId, 1,
                createdAt.plusSeconds(1),
                new EventType("EXECUTION_STARTED"),
                Optional.empty(), "durable-start",
                new InlineEventPayload(
                        new ObjectValue(Map.of())));
        Checkpoint started = new Checkpoint(
                taskFrame.id(), planId, revision.id(),
                revision.number(), 1,
                PlanExecutionState.ACTIVE,
                initial, List.of(), createdAt.plusSeconds(1));
        requireApplied(starts.start(new ExecutionStartRequest(
                planId, token, lease.fencingToken(),
                startEvent, started)));

        Map<PlanStepId, StepExecutionState> active =
                new LinkedHashMap<>(initial);
        active.put(a, StepExecutionState.ACTIVE);
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("durable-activation-a"),
                taskFrame.id(), planId, 2,
                createdAt.plusSeconds(2),
                new EventType("STEP_ACTIVATED"),
                Optional.of(startEvent.id()), "durable-activation-a",
                new InlineEventPayload(
                        new ObjectValue(Map.of())));
        Checkpoint activated = new Checkpoint(
                taskFrame.id(), planId, revision.id(),
                revision.number(), 2,
                PlanExecutionState.ACTIVE,
                active, List.of(), createdAt.plusSeconds(2));
        requireApplied(activations.activate(
                new StepActivationRequest(
                        planId, token, lease.fencingToken(),
                        revision.id(), revision.number(),
                        2, 1, a, activationEvent, activated)));
        return new DurableScenario(
                taskFrame, plan, a, b, lease);
    }

    static SingleTurnIntentPersisted persistDurableIntent(
            EffectIntentRepository intents,
            RecoveredActiveStep active) {
        PlanStepId stepId =
                active.recovery().activation().stepId();
        ToolCallId toolCallId = new ToolCallId(
                "durable-tool-" + stepId.value());
        EffectIntent intent = new EffectIntent(
                toolCallId, active.planId(), stepId,
                "literature.search",
                new ObjectValue(Map.of(
                        "query", new TextValue(
                                "durable query " + stepId.value()))));
        var persisted = intents.persist(new EffectIntentRequest(
                intent, active.lease().leaseToken(),
                active.lease().fencingToken(),
                active.recovery().activation()
                        .activationEvent().id()));
        if (persisted.outcome() != PersistenceOutcome.APPLIED
                && persisted.outcome() != PersistenceOutcome.REPLAYED) {
            throw new AssertionError(
                    "durable intent rejected: "
                            + persisted.outcome());
        }
        return new SingleTurnIntentPersisted(
                persisted.value().orElseThrow());
    }

    private static TaskFrame durableTaskFrame(Instant createdAt) {
        return new TaskFrame(
                new TaskFrameId("durable-loop-task-frame"),
                "execute durable two-step loop",
                List.of("product"),
                List.of("two completed steps"),
                List.of("persisted authority only"),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(), NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(1),
                                1024, 1024, 1),
                        Set.of()),
                createdAt);
    }

    private static PlanStep durableStep(
            PlanStepId id, Set<PlanStepId> dependencies) {
        return new PlanStep(
                id, "execute " + id.value(),
                "complete " + id.value(),
                dependencies, List.of("receipt"),
                new BoundedExecutionHints(
                        1, Duration.ofMinutes(1)));
    }

    private static void requireApplied(
            io.paperagent.v2.persistence.PersistenceResult<?> result) {
        if (result.outcome() != PersistenceOutcome.APPLIED) {
            throw new AssertionError(
                    "durable seed rejected: " + result.outcome());
        }
    }

    record LoopFixture(
            PlanId planId,
            AgentTurnProductContextResolver contexts,
            StepRecoveryRepository inspections,
            StepRecoverer recoverer,
            StepActivationComposer activation,
            SingleTurnStepKernel kernel,
            AuthenticatedLiteratureSearchEffectExecutionComposer effects,
            com.yanban.api.agent.v2.effect.project
                    .AuthenticatedProjectEffectExecutionComposer
                    projectEffects,
            AuthenticatedEffectDrivenStepProgressionComposer progression,
            BoundedStepReplanComposer replans,
            AuthenticatedPersistentPlanAgentLoopComposer composer) {
    }

    record ActiveCut(
            PlanStepId stepId,
            PersistedStepRecoveryActive recovery,
            RecoveredActiveStep active) {
    }

    record IntentCut(
            ToolCallId toolCallId,
            PersistedEffectIntent persisted,
            SingleTurnIntentPersisted outcome) {
    }

    record DurableScenario(
            TaskFrame taskFrame,
            Plan plan,
            PlanStepId firstStepId,
            PlanStepId secondStepId,
            LeaseRecord lease) {
    }
}
