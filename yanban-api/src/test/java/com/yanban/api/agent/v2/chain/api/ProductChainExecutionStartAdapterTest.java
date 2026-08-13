package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.start.FreshExecutionRecoveryRequired;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartRequest;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductChainExecutionStartAdapterTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-07T10:00:00Z");
    private static final String SHA = "a".repeat(64);

    private ChainWorkflowRepository workflow;
    private ProductPlanBootstrapRepositoryAdapter bootstraps;
    private ProductPlanBootstrapCodec codec;
    private FreshExecutionStarter starter;
    private ProductChainExecutionStartAdapter adapter;
    private TransitionRecord transition;
    private PlanBindingRecord binding;
    private PersistedPlanBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        workflow = mock(ChainWorkflowRepository.class);
        bootstraps = mock(ProductPlanBootstrapRepositoryAdapter.class);
        codec = mock(ProductPlanBootstrapCodec.class);
        starter = mock(FreshExecutionStarter.class);
        adapter = new ProductChainExecutionStartAdapter(
                workflow, bootstraps, codec, starter);
        transition = transition("task-1", "route-1", "b".repeat(64));
        binding = binding(transition, "plan-1", "frame-1", "revision-1");
        bootstrap = bootstrap("plan-1", "frame-1", "revision-1");
        when(workflow.findTransition(transition.transitionId()))
                .thenReturn(Optional.of(transition));
        when(workflow.findPlanBindings(binding.taskId()))
                .thenReturn(List.of(binding));
        when(bootstraps.find(new PlanId(binding.planId())))
                .thenReturn(Optional.of(bootstrap));
        when(codec.encode(bootstrap)).thenReturn(
                new ProductPlanBootstrapCodec.EncodedPayload(1, SHA, "{}"));
    }

    @Test
    void appliedAndRepositoryReplayReturnTheSameStableAuthority() {
        AtomicInteger calls = new AtomicInteger();
        when(starter.start(any())).thenAnswer(invocation -> {
            FreshExecutionStartRequest request = invocation.getArgument(0);
            PersistenceOutcome outcome = calls.getAndIncrement() == 0
                    ? PersistenceOutcome.APPLIED
                    : PersistenceOutcome.REPLAYED;
            return started(request, outcome, binding.planId());
        });

        var first = adapter.ensureStarted(binding);
        var replay = adapter.ensureStarted(binding);

        assertEquals(first, replay);
        assertEquals("STABLE_V2_EXECUTION_START", first.authorityType());
        assertEquals(first.startEventId(), first.authorityRef());
        assertEquals(binding.taskId(), first.taskId());
        assertEquals(transition.transitionId(), first.transitionId());
        assertEquals(2, first.checkpointVersion());

        ArgumentCaptor<FreshExecutionStartRequest> requests =
                ArgumentCaptor.forClass(FreshExecutionStartRequest.class);
        verify(starter, org.mockito.Mockito.times(2)).start(requests.capture());
        FreshExecutionStartRequest requested = requests.getAllValues().get(0);
        FreshExecutionStartRequest repeated = requests.getAllValues().get(1);
        assertEquals(PersistenceOutcome.APPLIED,
                requested.bootstrapResult().outcome());
        assertSame(bootstrap, requested.bootstrapResult().value().orElseThrow());
        assertEquals(requested.attempt(), repeated.attempt());
        FreshExecutionStartAttempt attempt =
                requested.attempt().orElseThrow();
        assertEquals(CREATED_AT.plusSeconds(30 * 60),
                attempt.leaseExpiresAt());
        assertEquals(CREATED_AT,
                attempt.eventDraft().occurredAt());
        assertEquals(CREATED_AT,
                attempt.checkpointCreatedAt());

        TransitionRecord other = transition(
                "task-1", "route-2", "c".repeat(64));
        PlanBindingRecord otherBinding = binding(
                other, "plan-1", "frame-1", "revision-1");
        when(workflow.findTransition(other.transitionId()))
                .thenReturn(Optional.of(other));
        when(workflow.findPlanBindings(otherBinding.taskId()))
                .thenReturn(List.of(otherBinding));
        doAnswer(invocation -> {
            FreshExecutionStartRequest request = invocation.getArgument(0);
            if (request == null) {
                return null;
            }
            return started(request, PersistenceOutcome.REPLAYED,
                    otherBinding.planId());
        }).when(starter).start(any());
        adapter.ensureStarted(otherBinding);
        ArgumentCaptor<FreshExecutionStartRequest> all =
                ArgumentCaptor.forClass(FreshExecutionStartRequest.class);
        verify(starter, org.mockito.Mockito.times(3)).start(all.capture());
        assertNotEquals(
                attempt.eventDraft().id(),
                all.getAllValues().get(2).attempt().orElseThrow()
                        .eventDraft().id());
    }

    @Test
    void recoveryRequiredFailsClosed() {
        when(starter.start(any())).thenReturn(
                new FreshExecutionRecoveryRequired(
                        new PlanId(binding.planId())));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.ensureStarted(binding));

        assertEquals("CHAIN_EXECUTION_START_NOT_COMMITTED",
                failure.getMessage());
    }

    @Test
    void mismatchedBootstrapIdentityFailsBeforeStarting() {
        PersistedPlanBootstrap wrong = bootstrap(
                "plan-other", "frame-1", "revision-1");
        when(bootstraps.find(new PlanId(binding.planId())))
                .thenReturn(Optional.of(wrong));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.ensureStarted(binding));

        assertEquals("CHAIN_EXECUTION_BOOTSTRAP_IDENTITY_MISMATCH",
                failure.getMessage());
        verify(starter, never()).start(any());
    }

    @Test
    void mismatchedPersistedStartIdentityFailsClosed() {
        when(starter.start(any())).thenAnswer(invocation -> started(
                invocation.getArgument(0), PersistenceOutcome.APPLIED,
                "plan-other"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.ensureStarted(binding));

        assertEquals("CHAIN_EXECUTION_START_IDENTITY_MISMATCH",
                failure.getMessage());
    }

    private static FreshExecutionStarted started(
            FreshExecutionStartRequest request,
            PersistenceOutcome outcome,
            String persistedPlanId) {
        FreshExecutionStartAttempt attempt = request.attempt().orElseThrow();
        PersistedPlanBootstrap bootstrap =
                request.bootstrapResult().value().orElseThrow();
        var frame = bootstrap.taskFrame();
        var plan = bootstrap.plan();
        var revision = plan.latestRevision();
        EventEnvelope event = new EventEnvelope(
                attempt.eventDraft().id(), frame.id(),
                new PlanId(persistedPlanId), 1,
                attempt.eventDraft().occurredAt(),
                attempt.eventDraft().type(),
                attempt.eventDraft().causationId(),
                attempt.eventDraft().correlationId(),
                attempt.eventDraft().payload());
        Checkpoint checkpoint = new Checkpoint(
                frame.id(), new PlanId(persistedPlanId), revision.id(),
                revision.number(), 1, PlanExecutionState.ACTIVE,
                Map.of(), List.of(), attempt.checkpointCreatedAt());
        return new FreshExecutionStarted(
                outcome,
                new PersistedExecutionStart(
                        new PlanId(persistedPlanId),
                        attempt.leaseOwnerId(), 7, event,
                        new VersionedCheckpoint(2, checkpoint)));
    }

    private static PersistedPlanBootstrap bootstrap(
            String planId, String taskFrameId, String revisionId) {
        TaskFrameId frameId = new TaskFrameId(taskFrameId);
        TaskFrame frame = new TaskFrame(
                frameId, "compile and run", List.of("Sort.java"),
                List.of("result"), List.of(), Optional.empty(),
                mock(ExecutionProfile.class), CREATED_AT.minusSeconds(3));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId(revisionId), frameId, 1,
                Optional.empty(), "initial", CREATED_AT.minusSeconds(2),
                List.of(), Map.of());
        Plan plan = new Plan(new PlanId(planId), frameId, List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                frameId, plan.id(), revision.id(), 1, 0,
                PlanExecutionState.NOT_STARTED, Map.of(), List.of(),
                CREATED_AT.minusSeconds(1));
        return new PersistedPlanBootstrap(
                frame, plan, new VersionedCheckpoint(1, checkpoint));
    }

    private static TransitionRecord transition(
            String taskId, String sourceDecisionId, String targetDigest) {
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.PLAN_CHANGE, taskId,
                sourceDecisionId, targetDigest).transitionId();
        return new TransitionRecord(
                transitionId, taskId, "event-" + sourceDecisionId,
                ChainTransitionType.PLAN_CHANGE, sourceDecisionId,
                targetDigest, CREATED_AT);
    }

    private static PlanBindingRecord binding(
            TransitionRecord transition,
            String planId,
            String taskFrameId,
            String revisionId) {
        return new PlanBindingRecord(
                "binding-" + transition.sourceDecisionId(),
                transition.taskId(),
                "binding-event-" + transition.sourceDecisionId(),
                "instruction-1", transition.sourceDecisionId(),
                taskFrameId, planId, revisionId, 1,
                "STABLE_V2_PLAN", revisionId, SHA,
                transition.transitionId(), CREATED_AT);
    }
}
