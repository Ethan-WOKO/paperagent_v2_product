package io.paperagent.v2.runtime.bootstrap;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.checkpoint.DeterministicInitialCheckpointFreezer;
import io.paperagent.v2.runtime.checkpoint.InitialCheckpointFreezeRequest;
import io.paperagent.v2.runtime.checkpoint.InitialCheckpointFreezer;
import io.paperagent.v2.runtime.planning.DeterministicInitialPlanFreezer;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.planning.InitialPlanFreezeRequest;
import io.paperagent.v2.runtime.planning.InitialPlanFreezer;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeRequest;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPersistentPlanBootstrapperTest {
    private static final PlanId PLAN_ID = new PlanId("plan-runtime-bootstrap");
    private static final PlanRevisionId REVISION_ID =
            new PlanRevisionId("revision-runtime-bootstrap");
    private static final TaskFrameId TASK_FRAME_ID =
            new TaskFrameId("task-runtime-bootstrap");
    private static final Instant TASK_CREATED_AT =
            Instant.parse("2026-07-24T10:00:00Z");
    private static final Instant PLAN_CREATED_AT =
            Instant.parse("2026-07-24T10:00:01Z");
    private static final Instant CHECKPOINT_CREATED_AT =
            Instant.parse("2026-07-24T10:00:02Z");

    @Test
    void realFreezersAndAtomicPortApplyThenReplayOriginalMarker() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        PersistentPlanBootstrapper bootstrapper =
                bootstrapper(persistence.planBootstraps());
        PersistentPlanBootstrapRequest request =
                request(declaredDecision("route-integration"));

        PersistenceResult<PersistedPlanBootstrap> first =
                bootstrapper.bootstrap(request);
        PersistenceResult<PersistedPlanBootstrap> retryAfterLostResponse =
                bootstrapper.bootstrap(request);

        assertEquals(PersistenceOutcome.APPLIED, first.outcome());
        PersistedPlanBootstrap snapshot = first.value().orElseThrow();
        assertEquals(1, snapshot.initialCheckpoint().version());
        assertEquals(request.taskFrameFreezeRequest().taskFrameId(),
                snapshot.taskFrame().id());
        assertEquals(request.planId(), snapshot.plan().id());
        assertEquals(request.initialRevisionId(),
                snapshot.plan().latestRevision().id());
        assertEquals(request.planCreatedAt(),
                snapshot.plan().latestRevision().createdAt());
        assertEquals(request.checkpointCreatedAt(),
                snapshot.initialCheckpoint().checkpoint().createdAt());

        assertEquals(PersistenceOutcome.REPLAYED, retryAfterLostResponse.outcome());
        assertEquals(snapshot, retryAfterLostResponse.value().orElseThrow());
        assertTrue(retryAfterLostResponse.failure().isEmpty());
        assertEquals(
                snapshot.taskFrame(),
                persistence.taskFrames()
                        .find(snapshot.taskFrame().id())
                        .value().orElseThrow());
        assertEquals(
                snapshot.plan(),
                persistence.plans().find(snapshot.plan().id())
                        .value().orElseThrow());
        assertEquals(
                snapshot.initialCheckpoint(),
                persistence.checkpoints().find(snapshot.plan().id())
                        .value().orElseThrow());
    }

    @Test
    void collaboratorsReceiveExactAuthorityInStrictOrderOnce() {
        List<String> order = new ArrayList<>();
        AtomicReference<TaskFrameFreezeRequest> taskRequest =
                new AtomicReference<>();
        AtomicReference<InitialPlanFreezeRequest> planRequest =
                new AtomicReference<>();
        AtomicReference<InitialCheckpointFreezeRequest> checkpointRequest =
                new AtomicReference<>();
        AtomicReference<TaskFrame> repositoryTaskFrame = new AtomicReference<>();
        AtomicReference<Plan> repositoryPlan = new AtomicReference<>();
        AtomicReference<Checkpoint> repositoryCheckpoint =
                new AtomicReference<>();

        TaskFrameFreezer taskFrameFreezer = request -> {
            order.add("taskFrame");
            taskRequest.set(request);
            return new DeterministicTaskFrameFreezer().freeze(request);
        };
        InitialPlanFreezer planFreezer = request -> {
            order.add("plan");
            planRequest.set(request);
            return new DeterministicInitialPlanFreezer().freeze(request);
        };
        InitialCheckpointFreezer checkpointFreezer = request -> {
            order.add("checkpoint");
            checkpointRequest.set(request);
            return new DeterministicInitialCheckpointFreezer().freeze(request);
        };
        PlanBootstrapRepository repository = (taskFrame, plan, checkpoint) -> {
            order.add("repository");
            repositoryTaskFrame.set(taskFrame);
            repositoryPlan.set(plan);
            repositoryCheckpoint.set(checkpoint);
            return PersistenceResult.applied(new PersistedPlanBootstrap(
                    taskFrame,
                    plan,
                    new VersionedCheckpoint(1, checkpoint)));
        };
        PersistentPlanBootstrapRequest request =
                request(declaredDecision("route-captured"));

        PersistenceResult<PersistedPlanBootstrap> result =
                new DefaultPersistentPlanBootstrapper(
                        taskFrameFreezer,
                        planFreezer,
                        checkpointFreezer,
                        repository)
                        .bootstrap(request);

        assertEquals(
                List.of("taskFrame", "plan", "checkpoint", "repository"),
                order);
        assertSame(request.taskFrameFreezeRequest(), taskRequest.get());

        InitialPlanFreezeRequest capturedPlanRequest = planRequest.get();
        assertSame(
                request.taskFrameFreezeRequest().routingDecision(),
                capturedPlanRequest.routingDecision());
        assertSame(repositoryTaskFrame.get(), capturedPlanRequest.taskFrame());
        assertSame(request.planId(), capturedPlanRequest.planId());
        assertSame(request.initialRevisionId(),
                capturedPlanRequest.initialRevisionId());
        assertSame(request.initialPlanDraft(), capturedPlanRequest.draft());
        assertSame(request.planCreatedAt(), capturedPlanRequest.createdAt());

        InitialCheckpointFreezeRequest capturedCheckpointRequest =
                checkpointRequest.get();
        assertSame(
                request.taskFrameFreezeRequest().routingDecision(),
                capturedCheckpointRequest.routingDecision());
        assertSame(repositoryTaskFrame.get(),
                capturedCheckpointRequest.taskFrame());
        assertSame(repositoryPlan.get(), capturedCheckpointRequest.plan());
        assertSame(
                request.checkpointCreatedAt(),
                capturedCheckpointRequest.createdAt());

        PersistedPlanBootstrap persisted = result.value().orElseThrow();
        assertSame(repositoryTaskFrame.get(), persisted.taskFrame());
        assertSame(repositoryPlan.get(), persisted.plan());
        assertSame(
                repositoryCheckpoint.get(),
                persisted.initialCheckpoint().checkpoint());
    }

    @Test
    void bothPersistentDecisionReasonsCanBootstrap() {
        List<RoutingDecision> decisions = List.of(
                declaredDecision("route-declared"),
                incompleteDecision("route-incomplete"));

        for (RoutingDecision decision : decisions) {
            InMemoryPersistence persistence = new InMemoryPersistence();
            PersistenceResult<PersistedPlanBootstrap> result =
                    bootstrapper(persistence.planBootstraps())
                            .bootstrap(request(decision));

            assertEquals(PersistenceOutcome.APPLIED, result.outcome());
            assertEquals(
                    decision.requestId(),
                    request(decision)
                            .taskFrameFreezeRequest()
                            .routingDecision()
                            .requestId());
        }
    }

    @Test
    void directRouteFailsBeforeEveryCollaborator() {
        AtomicInteger taskCalls = new AtomicInteger();
        AtomicInteger planCalls = new AtomicInteger();
        AtomicInteger checkpointCalls = new AtomicInteger();
        AtomicInteger repositoryCalls = new AtomicInteger();
        TaskFrameFreezer taskFreezer = request -> {
            taskCalls.incrementAndGet();
            return new DeterministicTaskFrameFreezer().freeze(request);
        };
        InitialPlanFreezer planFreezer = request -> {
            planCalls.incrementAndGet();
            return new DeterministicInitialPlanFreezer().freeze(request);
        };
        InitialCheckpointFreezer checkpointFreezer = request -> {
            checkpointCalls.incrementAndGet();
            return new DeterministicInitialCheckpointFreezer().freeze(request);
        };
        PlanBootstrapRepository repository = (taskFrame, plan, checkpoint) -> {
            repositoryCalls.incrementAndGet();
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT,
                    "unreachable");
        };
        DefaultPersistentPlanBootstrapper bootstrapper =
                new DefaultPersistentPlanBootstrapper(
                        taskFreezer,
                        planFreezer,
                        checkpointFreezer,
                        repository);

        PersistentPlanBootstrapValidationException failure = assertThrows(
                PersistentPlanBootstrapValidationException.class,
                () -> bootstrapper.bootstrap(
                        request(directDecision("route-direct"))));

        assertEquals(
                PersistentPlanBootstrapValidationCode.ROUTE_NOT_PERSISTENT,
                failure.code());
        assertEquals(
                "persistentPlanBootstrapRequest"
                        + ".taskFrameFreezeRequest.routingDecision.route",
                failure.path());
        assertEquals(0, taskCalls.get());
        assertEquals(0, planCalls.get());
        assertEquals(0, checkpointCalls.get());
        assertEquals(0, repositoryCalls.get());
    }

    @Test
    void eachFreezerFailurePropagatesAndStopsAllLaterCalls() {
        for (FailureStage stage : FailureStage.values()) {
            AtomicInteger taskCalls = new AtomicInteger();
            AtomicInteger planCalls = new AtomicInteger();
            AtomicInteger checkpointCalls = new AtomicInteger();
            AtomicInteger repositoryCalls = new AtomicInteger();
            IllegalStateException sentinel =
                    new IllegalStateException("sentinel-" + stage);

            TaskFrameFreezer taskFreezer = request -> {
                taskCalls.incrementAndGet();
                if (stage == FailureStage.TASK_FRAME) {
                    throw sentinel;
                }
                return new DeterministicTaskFrameFreezer().freeze(request);
            };
            InitialPlanFreezer planFreezer = request -> {
                planCalls.incrementAndGet();
                if (stage == FailureStage.PLAN) {
                    throw sentinel;
                }
                return new DeterministicInitialPlanFreezer().freeze(request);
            };
            InitialCheckpointFreezer checkpointFreezer = request -> {
                checkpointCalls.incrementAndGet();
                if (stage == FailureStage.CHECKPOINT) {
                    throw sentinel;
                }
                return new DeterministicInitialCheckpointFreezer()
                        .freeze(request);
            };
            PlanBootstrapRepository repository =
                    (taskFrame, plan, checkpoint) -> {
                        repositoryCalls.incrementAndGet();
                        return PersistenceResult.applied(
                                new PersistedPlanBootstrap(
                                        taskFrame,
                                        plan,
                                        new VersionedCheckpoint(
                                                1,
                                                checkpoint)));
                    };

            IllegalStateException propagated = assertThrows(
                    IllegalStateException.class,
                    () -> new DefaultPersistentPlanBootstrapper(
                            taskFreezer,
                            planFreezer,
                            checkpointFreezer,
                            repository)
                            .bootstrap(request(declaredDecision(
                                    "route-failure-" + stage))));

            assertSame(sentinel, propagated);
            assertEquals(1, taskCalls.get());
            assertEquals(
                    stage == FailureStage.TASK_FRAME ? 0 : 1,
                    planCalls.get());
            assertEquals(
                    stage == FailureStage.CHECKPOINT ? 1 : 0,
                    checkpointCalls.get());
            assertEquals(0, repositoryCalls.get());
        }
    }

    @Test
    void repositoryResultsAreReturnedRawWithOneCall() {
        List<PersistenceOutcome> outcomes = List.of(
                PersistenceOutcome.APPLIED,
                PersistenceOutcome.REPLAYED,
                PersistenceOutcome.REJECTED);
        for (PersistenceOutcome outcome : outcomes) {
            AtomicInteger repositoryCalls = new AtomicInteger();
            AtomicReference<PersistenceResult<PersistedPlanBootstrap>> supplied =
                    new AtomicReference<>();
            PlanBootstrapRepository repository =
                    (taskFrame, plan, checkpoint) -> {
                        repositoryCalls.incrementAndGet();
                        PersistedPlanBootstrap snapshot =
                                new PersistedPlanBootstrap(
                                        taskFrame,
                                        plan,
                                        new VersionedCheckpoint(1, checkpoint));
                        PersistenceResult<PersistedPlanBootstrap> result;
                        if (outcome == PersistenceOutcome.APPLIED) {
                            result = PersistenceResult.applied(snapshot);
                        } else if (outcome == PersistenceOutcome.REPLAYED) {
                            result = PersistenceResult.replayed(snapshot);
                        } else {
                            result = PersistenceResult.rejected(
                                    PersistenceErrorCode.CONFLICTING_REPLAY,
                                    "plan.id");
                        }
                        supplied.set(result);
                        return result;
                    };

            PersistenceResult<PersistedPlanBootstrap> actual =
                    bootstrapper(repository).bootstrap(request(
                            declaredDecision("route-result-" + outcome)));

            assertSame(supplied.get(), actual);
            assertEquals(outcome, actual.outcome());
            assertEquals(supplied.get().value(), actual.value());
            assertEquals(supplied.get().failure(), actual.failure());
            assertEquals(1, repositoryCalls.get());
        }
    }

    @Test
    void repositoryExceptionPropagatesWithoutCatchOrRetry() {
        AtomicInteger repositoryCalls = new AtomicInteger();
        IllegalStateException sentinel =
                new IllegalStateException("repository-sentinel");
        PlanBootstrapRepository repository = (taskFrame, plan, checkpoint) -> {
            repositoryCalls.incrementAndGet();
            throw sentinel;
        };

        IllegalStateException propagated = assertThrows(
                IllegalStateException.class,
                () -> bootstrapper(repository).bootstrap(
                        request(declaredDecision("route-repository-failure"))));

        assertSame(sentinel, propagated);
        assertEquals(1, repositoryCalls.get());
    }

    @Test
    void nullRequestAndConstructorNullsHaveStableSurfaces() {
        TaskFrameFreezer taskFreezer = new DeterministicTaskFrameFreezer();
        InitialPlanFreezer planFreezer =
                new DeterministicInitialPlanFreezer();
        InitialCheckpointFreezer checkpointFreezer =
                new DeterministicInitialCheckpointFreezer();
        PlanBootstrapRepository repository =
                (taskFrame, plan, checkpoint) -> null;

        PersistentPlanBootstrapValidationException requestFailure =
                assertThrows(
                        PersistentPlanBootstrapValidationException.class,
                        () -> new DefaultPersistentPlanBootstrapper(
                                taskFreezer,
                                planFreezer,
                                checkpointFreezer,
                                repository)
                                .bootstrap(null));
        assertEquals(
                PersistentPlanBootstrapValidationCode.REQUIRED_VALUE_MISSING,
                requestFailure.code());
        assertEquals(
                "persistentPlanBootstrapRequest",
                requestFailure.path());

        assertConstructorNull(
                () -> new DefaultPersistentPlanBootstrapper(
                        null,
                        planFreezer,
                        checkpointFreezer,
                        repository),
                "taskFrameFreezer");
        assertConstructorNull(
                () -> new DefaultPersistentPlanBootstrapper(
                        taskFreezer,
                        null,
                        checkpointFreezer,
                        repository),
                "initialPlanFreezer");
        assertConstructorNull(
                () -> new DefaultPersistentPlanBootstrapper(
                        taskFreezer,
                        planFreezer,
                        null,
                        repository),
                "initialCheckpointFreezer");
        assertConstructorNull(
                () -> new DefaultPersistentPlanBootstrapper(
                        taskFreezer,
                        planFreezer,
                        checkpointFreezer,
                        null),
                "planBootstrapRepository");
    }

    @Test
    void changedCallerAuthorityIsRejectedByPersistenceWithoutReplacement() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        PersistentPlanBootstrapper bootstrapper =
                bootstrapper(persistence.planBootstraps());
        RoutingDecision decision = declaredDecision("route-conflict");
        PersistentPlanBootstrapRequest original = request(decision);
        PersistenceResult<PersistedPlanBootstrap> applied =
                bootstrapper.bootstrap(original);
        PersistedPlanBootstrap originalSnapshot =
                applied.value().orElseThrow();

        List<PersistentPlanBootstrapRequest> conflicts = List.of(
                request(
                        decision,
                        new TaskFrameId("task-runtime-bootstrap-changed"),
                        taskFrameDraft("Prepare a verified update"),
                        TASK_CREATED_AT,
                        PLAN_ID,
                        REVISION_ID,
                        initialPlanDraft("Initial bootstrap plan"),
                        PLAN_CREATED_AT,
                        CHECKPOINT_CREATED_AT),
                request(
                        decision,
                        TASK_FRAME_ID,
                        taskFrameDraft("Prepare a verified update"),
                        TASK_CREATED_AT,
                        PLAN_ID,
                        new PlanRevisionId("revision-runtime-bootstrap-changed"),
                        initialPlanDraft("Initial bootstrap plan"),
                        PLAN_CREATED_AT,
                        CHECKPOINT_CREATED_AT),
                request(
                        decision,
                        TASK_FRAME_ID,
                        taskFrameDraft("Prepare a changed verified update"),
                        TASK_CREATED_AT,
                        PLAN_ID,
                        REVISION_ID,
                        initialPlanDraft("Initial bootstrap plan"),
                        PLAN_CREATED_AT,
                        CHECKPOINT_CREATED_AT),
                request(
                        decision,
                        TASK_FRAME_ID,
                        taskFrameDraft("Prepare a verified update"),
                        TASK_CREATED_AT,
                        PLAN_ID,
                        REVISION_ID,
                        initialPlanDraft("Changed initial bootstrap plan"),
                        PLAN_CREATED_AT,
                        CHECKPOINT_CREATED_AT),
                request(
                        decision,
                        TASK_FRAME_ID,
                        taskFrameDraft("Prepare a verified update"),
                        TASK_CREATED_AT.plusSeconds(1),
                        PLAN_ID,
                        REVISION_ID,
                        initialPlanDraft("Initial bootstrap plan"),
                        PLAN_CREATED_AT,
                        CHECKPOINT_CREATED_AT),
                request(
                        decision,
                        TASK_FRAME_ID,
                        taskFrameDraft("Prepare a verified update"),
                        TASK_CREATED_AT,
                        PLAN_ID,
                        REVISION_ID,
                        initialPlanDraft("Initial bootstrap plan"),
                        PLAN_CREATED_AT.plusSeconds(1),
                        CHECKPOINT_CREATED_AT),
                request(
                        decision,
                        TASK_FRAME_ID,
                        taskFrameDraft("Prepare a verified update"),
                        TASK_CREATED_AT,
                        PLAN_ID,
                        REVISION_ID,
                        initialPlanDraft("Initial bootstrap plan"),
                        PLAN_CREATED_AT,
                        CHECKPOINT_CREATED_AT.plusSeconds(1)));

        for (PersistentPlanBootstrapRequest conflict : conflicts) {
            PersistenceResult<PersistedPlanBootstrap> rejected =
                    bootstrapper.bootstrap(conflict);
            assertEquals(PersistenceOutcome.REJECTED, rejected.outcome());
            assertTrue(rejected.value().isEmpty());
            assertEquals(
                    PersistenceErrorCode.CONFLICTING_REPLAY,
                    rejected.failure().orElseThrow().code());
            assertEquals("plan.id", rejected.failure().orElseThrow().path());
            assertNotEquals(original, conflict);
        }

        PersistenceResult<PersistedPlanBootstrap> originalReplay =
                bootstrapper.bootstrap(original);
        assertEquals(PersistenceOutcome.REPLAYED, originalReplay.outcome());
        assertEquals(originalSnapshot, originalReplay.value().orElseThrow());
    }

    @Test
    void crossInstanceAndInterleavedRequestsRemainDeterministic() {
        PersistentPlanBootstrapRequest original =
                request(declaredDecision("route-deterministic"));
        InMemoryPersistence sharedPersistence = new InMemoryPersistence();
        PersistentPlanBootstrapper firstInstance =
                bootstrapper(sharedPersistence.planBootstraps());
        PersistentPlanBootstrapper secondInstance =
                bootstrapper(sharedPersistence.planBootstraps());

        PersistenceResult<PersistedPlanBootstrap> first =
                firstInstance.bootstrap(original);
        PersistenceResult<PersistedPlanBootstrap> second =
                secondInstance.bootstrap(original);

        assertEquals(PersistenceOutcome.APPLIED, first.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, second.outcome());
        assertEquals(first.value(), second.value());

        InMemoryPersistence independentPersistence = new InMemoryPersistence();
        PersistenceResult<PersistedPlanBootstrap> independent =
                bootstrapper(independentPersistence.planBootstraps())
                        .bootstrap(original);
        assertEquals(PersistenceOutcome.APPLIED, independent.outcome());
        assertEquals(first.value(), independent.value());

        InMemoryPersistence interleavedPersistence = new InMemoryPersistence();
        PersistentPlanBootstrapper interleaved =
                bootstrapper(interleavedPersistence.planBootstraps());
        PersistentPlanBootstrapRequest requestA = request(
                declaredDecision("route-interleaved-a"),
                new TaskFrameId("task-interleaved-a"),
                taskFrameDraft("Prepare update A"),
                TASK_CREATED_AT,
                new PlanId("plan-interleaved-a"),
                new PlanRevisionId("revision-interleaved-a"),
                initialPlanDraft("Plan A"),
                PLAN_CREATED_AT,
                CHECKPOINT_CREATED_AT);
        PersistentPlanBootstrapRequest requestB = request(
                incompleteDecision("route-interleaved-b"),
                new TaskFrameId("task-interleaved-b"),
                taskFrameDraft("Prepare update B"),
                TASK_CREATED_AT.plusSeconds(10),
                new PlanId("plan-interleaved-b"),
                new PlanRevisionId("revision-interleaved-b"),
                initialPlanDraft("Plan B"),
                PLAN_CREATED_AT.plusSeconds(10),
                CHECKPOINT_CREATED_AT.plusSeconds(10));

        PersistenceResult<PersistedPlanBootstrap> aFirst =
                interleaved.bootstrap(requestA);
        PersistenceResult<PersistedPlanBootstrap> bFirst =
                interleaved.bootstrap(requestB);
        PersistenceResult<PersistedPlanBootstrap> aReplay =
                interleaved.bootstrap(requestA);

        assertEquals(PersistenceOutcome.APPLIED, aFirst.outcome());
        assertEquals(PersistenceOutcome.APPLIED, bFirst.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, aReplay.outcome());
        assertEquals(aFirst.value(), aReplay.value());
        assertFalse(aFirst.value().equals(bFirst.value()));
        assertEquals("Prepare update A",
                requestA.taskFrameFreezeRequest().draft().objective());
        assertEquals("Prepare update B",
                requestB.taskFrameFreezeRequest().draft().objective());
    }

    private static PersistentPlanBootstrapper bootstrapper(
            PlanBootstrapRepository repository) {
        return new DefaultPersistentPlanBootstrapper(
                new DeterministicTaskFrameFreezer(),
                new DeterministicInitialPlanFreezer(),
                new DeterministicInitialCheckpointFreezer(),
                repository);
    }

    private static PersistentPlanBootstrapRequest request(
            RoutingDecision decision) {
        return request(
                decision,
                TASK_FRAME_ID,
                taskFrameDraft("Prepare a verified update"),
                TASK_CREATED_AT,
                PLAN_ID,
                REVISION_ID,
                initialPlanDraft("Initial bootstrap plan"),
                PLAN_CREATED_AT,
                CHECKPOINT_CREATED_AT);
    }

    private static PersistentPlanBootstrapRequest request(
            RoutingDecision decision,
            TaskFrameId taskFrameId,
            TaskFrameDraft taskFrameDraft,
            Instant taskCreatedAt,
            PlanId planId,
            PlanRevisionId revisionId,
            InitialPlanDraft planDraft,
            Instant planCreatedAt,
            Instant checkpointCreatedAt) {
        TaskFrameFreezeRequest taskRequest = new TaskFrameFreezeRequest(
                decision,
                taskFrameId,
                taskFrameDraft,
                Optional.empty(),
                executionProfile(),
                taskCreatedAt);
        return new PersistentPlanBootstrapRequest(
                taskRequest,
                planId,
                revisionId,
                planDraft,
                planCreatedAt,
                checkpointCreatedAt);
    }

    private static TaskFrameDraft taskFrameDraft(String objective) {
        return new TaskFrameDraft(
                objective,
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve citations"));
    }

    private static InitialPlanDraft initialPlanDraft(String reason) {
        PlanStepId stepId = new PlanStepId("step-runtime-bootstrap");
        return new InitialPlanDraft(
                reason,
                List.of(new PlanStep(
                        stepId,
                        "Prepare update",
                        "Verify update",
                        Set.of(),
                        List.of("update verified"),
                        new BoundedExecutionHints(2, Duration.ofMinutes(2)))));
    }

    private static ExecutionProfile executionProfile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        512 * 1024 * 1024L,
                        1024 * 1024L,
                        8),
                Set.of());
    }

    private static RoutingDecision declaredDecision(String requestId) {
        return new RoutingDecision(
                new RoutingRequestId(requestId),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.PROJECT_FILE_ACCESS));
    }

    private static RoutingDecision incompleteDecision(String requestId) {
        return new RoutingDecision(
                new RoutingRequestId(requestId),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.INCOMPLETE_ASSESSMENT,
                Set.of());
    }

    private static RoutingDecision directDecision(String requestId) {
        return new RoutingDecision(
                new RoutingRequestId(requestId),
                Route.DIRECT,
                RoutingDecisionReason.DIRECT_ELIGIBLE,
                Set.of());
    }

    private static void assertConstructorNull(
            Runnable invocation,
            String expectedMessage) {
        NullPointerException failure =
                assertThrows(NullPointerException.class, invocation::run);
        assertEquals(expectedMessage, failure.getMessage());
    }

    private enum FailureStage {
        TASK_FRAME,
        PLAN,
        CHECKPOINT
    }
}
