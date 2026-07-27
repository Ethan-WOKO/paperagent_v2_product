package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextSnapshot;
import io.paperagent.v2.workspace.LocalWorkspaceProvider;
import io.paperagent.v2.workspace.ProjectFileSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspacePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.T0;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.committed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionContextCompositionConcurrencyTest {
    private static final int CALLERS = 32;
    private static final ProjectVersionRef SOURCE_VERSION =
            new ProjectVersionRef(
                    "runtime-concurrency-project",
                    "runtime-concurrency-version");
    private static final WorkspaceMaterializationSpec SPEC =
            new WorkspaceMaterializationSpec(
                    new WorkspaceId("runtime-concurrency-workspace"),
                    SOURCE_VERSION,
                    new WorkspaceMaterializationLimits(
                            4096,
                            16384,
                            16));
    private static final byte[] PAPER =
            "concurrent paper".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOTES =
            "concurrent notes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path providerRoot;

    @Test
    void thirtyTwoCallersConvergeFromTheSameRealNoneObservation()
            throws Exception {
        AtomicInteger sourceLoads = new AtomicInteger();
        LocalWorkspaceProvider realWorkspace = new LocalWorkspaceProvider(
                providerRoot,
                requested -> {
                    assertEquals(SOURCE_VERSION, requested);
                    sourceLoads.incrementAndGet();
                    return sourceSnapshot();
                });
        CountingWorkspacePort workspace =
                new CountingWorkspacePort(realWorkspace);
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        SeededExecution seeded = seedCommitted(
                persistence,
                "runtime-context-concurrency",
                SOURCE_VERSION);
        FirstNoneGateRepository contexts =
                new FirstNoneGateRepository(
                        persistence.planExecutionContexts(),
                        CALLERS);
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        persistence.leases(),
                        workspace);
        PlanExecutionContextCompositionRequest request =
                new PlanExecutionContextCompositionRequest(
                        seeded.committed().planId(),
                        Optional.of(SPEC),
                        Optional.of(seeded.attempt()));

        List<PlanExecutionContextReady> ready = runBounded(
                CALLERS,
                contexts,
                () -> assertInstanceOf(
                        PlanExecutionContextReady.class,
                        composer.compose(request)));

        assertEquals(CALLERS, ready.size());
        assertEquals(CALLERS, contexts.firstNoneObservations());
        PersistedPlanExecutionContextConfirmed authority =
                ready.get(0).persistedContext();
        VerifiedWorkspaceMaterialization verified =
                ready.get(0).verifiedWorkspace();
        assertTrue(ready.stream()
                .allMatch(result -> result.persistedContext()
                        .equals(authority)));
        assertTrue(ready.stream()
                .allMatch(result -> result.verifiedWorkspace()
                        .equals(verified)));
        Map<PlanExecutionContextCompositionResolution, Long> histogram =
                ready.stream().collect(java.util.stream.Collectors.groupingBy(
                        PlanExecutionContextReady::resolution,
                        java.util.stream.Collectors.counting()));
        assertEquals(
                1L,
                histogram.getOrDefault(
                        PlanExecutionContextCompositionResolution
                                .CONFIRM_APPLIED,
                        0L));
        assertEquals(
                31L,
                histogram.getOrDefault(
                                PlanExecutionContextCompositionResolution
                                        .CONFIRM_REPLAYED,
                                0L)
                        + histogram.getOrDefault(
                                PlanExecutionContextCompositionResolution
                                        .OBSERVED_CONCURRENT_CONFIRMATION,
                                0L));
        assertEquals(
                0L,
                histogram.getOrDefault(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONFIRMED,
                        0L));
        assertEquals(
                0L,
                histogram.getOrDefault(
                        PlanExecutionContextCompositionResolution
                                .RECONCILED_AFTER_RESPONSE_LOSS,
                        0L));

        PersistedPlanExecutionContextConfirmed stored =
                assertInstanceOf(
                        PersistedPlanExecutionContextConfirmed.class,
                        persistence.planExecutionContexts()
                                .inspect(seeded.committed().planId())
                                .value()
                                .orElseThrow());
        assertEquals(authority, stored);
        assertEquals(SPEC, stored.materializationSpec());
        assertEquals(
                seeded.lease().ownerId(),
                stored.reservation().leaseOwnerId());
        assertEquals(
                seeded.lease().fencingToken(),
                stored.reservation().fencingToken());
        assertEquals(seeded.lease().ownerId(), stored.leaseOwnerId());
        assertEquals(seeded.lease().fencingToken(), stored.fencingToken());
        assertEquals(
                stored.sourceManifestFingerprint(),
                verified.sourceManifestFingerprint());
        assertEquals(verified, realWorkspace.inspectMaterialization(SPEC));
        assertEquals(1, sourceLoads.get());
        assertTrue(workspace.materializeCalls() >= 1);
        assertTrue(workspace.materializeCalls() <= CALLERS);
        assertPublishedShape(realWorkspace, verified);
    }

    @Test
    void samePlanConflictingSpecsHasOnePermanentWinner()
            throws Exception {
        Path root = providerRoot.resolve("same-plan-conflict");
        Files.createDirectory(root);
        AtomicInteger sourceLoads = new AtomicInteger();
        LocalWorkspaceProvider realWorkspace = new LocalWorkspaceProvider(
                root,
                requested -> {
                    assertEquals(SOURCE_VERSION, requested);
                    sourceLoads.incrementAndGet();
                    return sourceSnapshot();
                });
        CountingWorkspacePort firstWorkspace =
                new CountingWorkspacePort(realWorkspace);
        CountingWorkspacePort secondWorkspace =
                new CountingWorkspacePort(realWorkspace);
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        SeededExecution seeded = seedCommitted(
                persistence,
                "same-plan-conflict",
                SOURCE_VERSION);
        WorkspaceMaterializationSpec firstSpec =
                workspaceSpec("conflict-first");
        WorkspaceMaterializationSpec secondSpec =
                workspaceSpec("conflict-second");
        FirstNoneGateRepository contexts =
                new FirstNoneGateRepository(
                        persistence.planExecutionContexts(),
                        2);
        DefaultPlanExecutionContextComposer firstComposer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        persistence.leases(),
                        firstWorkspace);
        DefaultPlanExecutionContextComposer secondComposer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        persistence.leases(),
                        secondWorkspace);

        List<ConcurrentAttempt> attempts = runBounded(
                List.of(
                        () -> captureCompose(
                                firstComposer,
                                seeded,
                                firstSpec),
                        () -> captureCompose(
                                secondComposer,
                                seeded,
                                secondSpec)),
                contexts);

        assertEquals(
                1,
                attempts.stream()
                        .filter(attempt -> attempt.ready() != null)
                        .count());
        assertEquals(
                1,
                attempts.stream()
                        .filter(attempt -> attempt.failure() != null)
                        .count());
        RuntimeException loser = attempts.stream()
                .map(ConcurrentAttempt::failure)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        PlanExecutionContextCompositionProtocolException protocol =
                assertInstanceOf(
                        PlanExecutionContextCompositionProtocolException.class,
                        loser);
        assertEquals(
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_CONTEXT_AUTHORITY,
                protocol.code());
        PlanExecutionContextReady winner = attempts.stream()
                .map(ConcurrentAttempt::ready)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        assertEquals(
                winner.persistedContext(),
                persistence.planExecutionContexts()
                        .inspect(seeded.committed().planId())
                        .value()
                        .orElseThrow());
        assertTrue(
                winner.persistedContext().materializationSpec()
                                .equals(firstSpec)
                        || winner.persistedContext()
                                .materializationSpec()
                                .equals(secondSpec));
        assertEquals(1, sourceLoads.get());
        if (winner.persistedContext()
                .materializationSpec()
                .equals(firstSpec)) {
            assertTrue(firstWorkspace.interactionCalls() > 0);
            assertEquals(0, secondWorkspace.interactionCalls());
        } else {
            assertTrue(secondWorkspace.interactionCalls() > 0);
            assertEquals(0, firstWorkspace.interactionCalls());
        }
        assertEquals(
                1,
                firstWorkspace.materializeCalls()
                        + secondWorkspace.materializeCalls());
        assertRootShape(root, 1);
    }

    @Test
    void differentPlansWithSameWorkspaceIdShareOneActiveFact()
            throws Exception {
        Path root = providerRoot.resolve("same-workspace-id");
        Files.createDirectory(root);
        AtomicInteger sourceLoads = new AtomicInteger();
        LocalWorkspaceProvider realWorkspace = new LocalWorkspaceProvider(
                root,
                requested -> {
                    assertEquals(SOURCE_VERSION, requested);
                    sourceLoads.incrementAndGet();
                    return sourceSnapshot();
                });
        CountingWorkspacePort firstWorkspace =
                new CountingWorkspacePort(realWorkspace);
        CountingWorkspacePort secondWorkspace =
                new CountingWorkspacePort(realWorkspace);
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        SeededExecution first = seedCommitted(
                persistence,
                "shared-workspace-first",
                SOURCE_VERSION);
        SeededExecution second = seedCommitted(
                persistence,
                "shared-workspace-second",
                SOURCE_VERSION);
        WorkspaceMaterializationSpec shared =
                workspaceSpec("shared-workspace");
        FirstNoneGateRepository contexts =
                new FirstNoneGateRepository(
                        persistence.planExecutionContexts(),
                        2);
        DefaultPlanExecutionContextComposer firstComposer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        persistence.leases(),
                        firstWorkspace);
        DefaultPlanExecutionContextComposer secondComposer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        persistence.leases(),
                        secondWorkspace);

        List<PlanExecutionContextCompositionOutcome> outcomes = runBounded(
                List.of(
                        () -> compose(firstComposer, first, shared),
                        () -> compose(secondComposer, second, shared)),
                contexts);

        assertEquals(2, outcomes.size());
        assertEquals(
                1,
                outcomes.stream()
                        .filter(PlanExecutionContextReady.class::isInstance)
                        .count());
        assertEquals(
                1,
                outcomes.stream()
                        .filter(PlanExecutionContextPersistenceRejected.class
                                ::isInstance)
                        .count());
        PlanExecutionContextReady winner = outcomes.stream()
                .filter(PlanExecutionContextReady.class::isInstance)
                .map(PlanExecutionContextReady.class::cast)
                .findFirst()
                .orElseThrow();
        PlanExecutionContextPersistenceRejected loser =
                outcomes.stream()
                        .filter(PlanExecutionContextPersistenceRejected.class
                                ::isInstance)
                        .map(PlanExecutionContextPersistenceRejected.class
                                ::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(shared, winner.persistedContext()
                .materializationSpec());
        assertEquals(
                PlanExecutionContextCompositionStage.RESERVE,
                loser.stage());
        assertEquals(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                loser.failure().code());
        assertEquals(
                "request.materializationSpec.workspaceId",
                loser.failure().path());
        assertEquals(
                winner.persistedContext(),
                persistence.planExecutionContexts()
                        .inspect(winner.planId())
                        .value()
                        .orElseThrow());
        PlanId losingPlan = winner.planId().equals(
                first.committed().planId())
                ? second.committed().planId()
                : first.committed().planId();
        assertEquals(
                PersistenceOutcome.REJECTED,
                persistence.planExecutionContexts()
                        .inspect(losingPlan)
                        .outcome());
        assertEquals(1, sourceLoads.get());
        if (winner.planId().equals(first.committed().planId())) {
            assertTrue(firstWorkspace.interactionCalls() > 0);
            assertEquals(0, secondWorkspace.interactionCalls());
        } else {
            assertTrue(secondWorkspace.interactionCalls() > 0);
            assertEquals(0, firstWorkspace.interactionCalls());
        }
        assertEquals(
                1,
                firstWorkspace.materializeCalls()
                        + secondWorkspace.materializeCalls());
        assertRootShape(root, 1);
    }

    @Test
    void differentPlansAndWorkspaceIdsPublishIndependently()
            throws Exception {
        Path root = providerRoot.resolve("independent-workspaces");
        Files.createDirectory(root);
        AtomicInteger sourceLoads = new AtomicInteger();
        LocalWorkspaceProvider realWorkspace = new LocalWorkspaceProvider(
                root,
                requested -> {
                    assertEquals(SOURCE_VERSION, requested);
                    sourceLoads.incrementAndGet();
                    return sourceSnapshot();
                });
        CountingWorkspacePort workspace =
                new CountingWorkspacePort(realWorkspace);
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        SeededExecution first = seedCommitted(
                persistence,
                "independent-first",
                SOURCE_VERSION);
        SeededExecution second = seedCommitted(
                persistence,
                "independent-second",
                SOURCE_VERSION);
        WorkspaceMaterializationSpec firstSpec =
                workspaceSpec("independent-first");
        WorkspaceMaterializationSpec secondSpec =
                workspaceSpec("independent-second");
        FirstNoneGateRepository contexts =
                new FirstNoneGateRepository(
                        persistence.planExecutionContexts(),
                        2);
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        persistence.leases(),
                        workspace);

        List<PlanExecutionContextReady> ready = runBounded(
                List.of(
                        () -> composeReady(
                                composer,
                                first,
                                firstSpec),
                        () -> composeReady(
                                composer,
                                second,
                                secondSpec)),
                contexts);

        assertEquals(2, ready.size());
        assertFalse(
                ready.get(0).verifiedWorkspace()
                        .workspace()
                        .equals(ready.get(1)
                                .verifiedWorkspace()
                                .workspace()));
        assertEquals(2, sourceLoads.get());
        assertEquals(2, workspace.materializeCalls());
        assertRootShape(root, 2);
    }

    @Test
    void ioInProgressAcrossLeaseExpiryConvergesOnNewFence()
            throws Exception {
        Path root = providerRoot.resolve("io-expiry-takeover");
        Files.createDirectory(root);
        MutableClock clock = new MutableClock(T0);
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        SeededExecution seeded = seedCommitted(
                persistence,
                "io-expiry-takeover",
                SOURCE_VERSION);
        WorkspaceMaterializationSpec spec =
                workspaceSpec("io-expiry-takeover");
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        AtomicInteger sourceLoads = new AtomicInteger();
        LocalWorkspaceProvider realWorkspace = new LocalWorkspaceProvider(
                root,
                requested -> {
                    assertEquals(SOURCE_VERSION, requested);
                    sourceLoads.incrementAndGet();
                    sourceEntered.countDown();
                    await(releaseSource);
                    return sourceSnapshot();
                });
        CountingWorkspacePort workspace =
                new CountingWorkspacePort(realWorkspace);
        String takeoverOwner = "io-takeover-owner";
        TakeoverSignalingLeaseRepository leases =
                new TakeoverSignalingLeaseRepository(
                        persistence.leases(),
                        takeoverOwner);
        RecordingContextRepository contexts =
                new RecordingContextRepository(
                        persistence.planExecutionContexts());
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        leases,
                        workspace);
        PlanExecutionContextLeaseAttempt takeoverAttempt =
                new PlanExecutionContextLeaseAttempt(
                        takeoverOwner,
                        "io-takeover-token",
                        T0.plusSeconds(1200));
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        List<Thread> workers = new CopyOnWriteArrayList<>();
        AtomicInteger threadSequence = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                2,
                operation -> {
                    Thread worker = new Thread(
                            operation,
                            "io-takeover-worker-"
                                    + threadSequence.incrementAndGet());
                    workers.add(worker);
                    return worker;
                });
        List<Future<PlanExecutionContextCompositionOutcome>> futures =
                new ArrayList<>();
        try {
            Future<PlanExecutionContextCompositionOutcome> original =
                    executor.submit(() -> compose(
                            composer,
                            seeded,
                            spec));
            futures.add(original);
            assertTrue(sourceEntered.await(
                    remaining(deadline),
                    TimeUnit.NANOSECONDS));
            clock.set(T0.plusSeconds(601));
            Future<PlanExecutionContextCompositionOutcome> takeover =
                    executor.submit(() -> composer.compose(
                            new PlanExecutionContextCompositionRequest(
                                    seeded.committed().planId(),
                                    Optional.of(spec),
                                    Optional.of(takeoverAttempt))));
            futures.add(takeover);
            assertTrue(leases.awaitTakeover(
                    remaining(deadline)));
            releaseSource.countDown();

            PlanExecutionContextCompositionOutcome originalOutcome =
                    original.get(
                            remaining(deadline),
                            TimeUnit.NANOSECONDS);
            PlanExecutionContextReady ready = assertInstanceOf(
                    PlanExecutionContextReady.class,
                    takeover.get(
                            remaining(deadline),
                            TimeUnit.NANOSECONDS));

            if (originalOutcome
                    instanceof PlanExecutionContextPersistenceRejected stale) {
                assertEquals(
                        PlanExecutionContextCompositionStage.CONFIRM,
                        stale.stage());
                assertOldConfirmRejected(
                        stale.failure().code(),
                        stale.failure().path());
            } else {
                PlanExecutionContextReady observedTakeover =
                        assertInstanceOf(
                                PlanExecutionContextReady.class,
                                originalOutcome);
                assertEquals(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONCURRENT_CONFIRMATION,
                        observedTakeover.resolution());
                assertEquals(
                        ready.persistedContext(),
                        observedTakeover.persistedContext());
            }
            ConfirmObservation oldConfirm = contexts.confirmations().stream()
                    .filter(observation -> observation.request()
                            .leaseToken()
                            .equals(seeded.attempt().leaseToken()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    seeded.lease().fencingToken(),
                    oldConfirm.request().fencingToken());
            assertEquals(
                    PersistenceOutcome.REJECTED,
                    oldConfirm.result().outcome());
            var oldFailure = oldConfirm.result()
                    .failure()
                    .orElseThrow();
            assertOldConfirmRejected(
                    oldFailure.code(),
                    oldFailure.path());
            ConfirmObservation takeoverConfirm =
                    contexts.confirmations().stream()
                            .filter(observation -> observation.request()
                                    .leaseToken()
                                    .equals(takeoverAttempt.leaseToken()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    takeoverConfirm.result().outcome());
            assertEquals(2, contexts.confirmations().size());
            assertEquals(
                    seeded.lease().fencingToken(),
                    ready.persistedContext()
                            .reservation()
                            .fencingToken());
            assertEquals(
                    takeoverOwner,
                    ready.persistedContext().leaseOwnerId());
            assertTrue(
                    ready.persistedContext().fencingToken()
                            > seeded.lease().fencingToken());
            assertEquals(1, sourceLoads.get());
            assertEquals(1, workspace.materializeCalls());
            assertEquals(
                    ready.persistedContext(),
                    contexts
                            .inspect(seeded.committed().planId())
                            .value()
                            .orElseThrow());
            assertRootShape(root, 1);
        } finally {
            releaseSource.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    Math.max(1, remaining(deadline)),
                    TimeUnit.NANOSECONDS));
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    worker.join(
                            Math.max(
                                    1,
                                    TimeUnit.NANOSECONDS.toMillis(
                                            remaining(deadline))));
                }
            }
            assertTrue(workers.stream().noneMatch(Thread::isAlive));
        }
    }

    private static void assertOldConfirmRejected(
            PersistenceErrorCode code,
            String path) {
        boolean staleToken =
                code == PersistenceErrorCode.LEASE_TOKEN_INVALID
                        && path.equals("request.leaseToken");
        boolean conflictingReplay =
                code == PersistenceErrorCode.CONFLICTING_REPLAY
                        && path.equals("request.planId");
        assertTrue(staleToken || conflictingReplay);
    }

    private static PlanExecutionContextReady composeReady(
            DefaultPlanExecutionContextComposer composer,
            SeededExecution seeded,
            WorkspaceMaterializationSpec spec) {
        return assertInstanceOf(
                PlanExecutionContextReady.class,
                compose(composer, seeded, spec));
    }

    private static PlanExecutionContextCompositionOutcome compose(
            DefaultPlanExecutionContextComposer composer,
            SeededExecution seeded,
            WorkspaceMaterializationSpec spec) {
        return composer.compose(new PlanExecutionContextCompositionRequest(
                seeded.committed().planId(),
                Optional.of(spec),
                Optional.of(seeded.attempt())));
    }

    private static ConcurrentAttempt captureCompose(
            DefaultPlanExecutionContextComposer composer,
            SeededExecution seeded,
            WorkspaceMaterializationSpec spec) {
        try {
            return new ConcurrentAttempt(
                    composeReady(composer, seeded, spec),
                    null);
        } catch (RuntimeException failure) {
            return new ConcurrentAttempt(null, failure);
        }
    }

    private static WorkspaceMaterializationSpec workspaceSpec(
            String suffix) {
        return new WorkspaceMaterializationSpec(
                new WorkspaceId("runtime-workspace-" + suffix),
                SOURCE_VERSION,
                SPEC.limits());
    }

    private static void assertRootShape(
            Path root,
            int expectedContainers) throws IOException {
        List<Path> containers = children(root);
        assertEquals(expectedContainers, containers.size());
        for (Path container : containers) {
            assertTrue(
                    container.getFileName().toString().startsWith("ws-"));
            assertEquals(
                    List.of("data", "staging"),
                    childNames(container));
            assertTrue(
                    childNames(container.resolve("staging")).isEmpty());
        }
        assertFalse(tree(root).stream()
                .anyMatch(path -> path.startsWith("pending-")));
    }

    private static <T> List<T> runBounded(
            int callers,
            FirstNoneGateRepository gate,
            Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int index = 0; index < callers; index++) {
            tasks.add(task);
        }
        return runBounded(tasks, gate);
    }

    private static <T> List<T> runBounded(
            List<Callable<T>> tasks,
            FirstNoneGateRepository gate) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        List<Thread> workers = new CopyOnWriteArrayList<>();
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory threads = operation -> {
            Thread worker = new Thread(
                    operation,
                    "context-composition-worker-"
                            + threadSequence.incrementAndGet());
            workers.add(worker);
            return worker;
        };
        ExecutorService executor =
                Executors.newFixedThreadPool(tasks.size(), threads);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }
            assertTrue(gate.awaitAll(remaining(deadline)));
            gate.release();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(
                        remaining(deadline),
                        TimeUnit.NANOSECONDS));
            }
            return List.copyOf(results);
        } finally {
            gate.release();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    Math.max(1, remaining(deadline)),
                    TimeUnit.NANOSECONDS));
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    worker.join(
                            Math.max(
                                    1,
                                    TimeUnit.NANOSECONDS.toMillis(
                                            remaining(deadline))));
                }
            }
            assertTrue(workers.stream().noneMatch(Thread::isAlive));
        }
    }

    private static long remaining(long deadline) {
        return Math.max(1, deadline - System.nanoTime());
    }

    private static SeededExecution seedCommitted(
            InMemoryPersistence persistence,
            String suffix,
            ProjectVersionRef sourceVersion) {
        PersistedExecutionStartCommitted template = committed(
                suffix,
                Optional.of(sourceVersion));
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.planBootstraps()
                        .bootstrap(
                                template.bootstrap().taskFrame(),
                                template.bootstrap().plan(),
                                template.bootstrap()
                                        .initialCheckpoint()
                                        .checkpoint())
                        .outcome());
        String owner = "concurrency-owner-" + suffix;
        String token = "concurrency-token-" + suffix;
        Instant expiresAt = T0.plusSeconds(600);
        LeaseRecord lease = persistence.leases()
                .acquire(
                        template.planId(),
                        owner,
                        token,
                        expiresAt)
                .value()
                .orElseThrow();
        var start = template.executionStart();
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.executionStarts()
                        .start(new ExecutionStartRequest(
                                template.planId(),
                                token,
                                lease.fencingToken(),
                                start.startEvent(),
                                start.startedCheckpoint().checkpoint()))
                        .outcome());
        PersistedExecutionStartCommitted committed =
                assertInstanceOf(
                        PersistedExecutionStartCommitted.class,
                        persistence.executionStartRecovery()
                                .inspect(template.planId())
                                .value()
                                .orElseThrow());
        return new SeededExecution(
                committed,
                lease,
                new PlanExecutionContextLeaseAttempt(
                        owner,
                        token,
                        expiresAt));
    }

    private void assertPublishedShape(
            LocalWorkspaceProvider workspace,
            VerifiedWorkspaceMaterialization verified)
            throws IOException {
        assertEquals(
                List.of("docs/notes.md", "paper.txt"),
                workspace.list(verified.workspace()).stream()
                        .map(stat -> stat.path().value())
                        .toList());
        assertArrayEquals(
                PAPER,
                workspace.read(
                        verified.workspace(),
                        new ProjectPath("paper.txt")));
        assertArrayEquals(
                NOTES,
                workspace.read(
                        verified.workspace(),
                        new ProjectPath("docs/notes.md")));
        List<Path> rootChildren = children(providerRoot);
        assertEquals(1, rootChildren.size());
        Path container = rootChildren.get(0);
        assertTrue(container.getFileName().toString().startsWith("ws-"));
        assertEquals(List.of("data", "staging"), childNames(container));
        assertTrue(childNames(container.resolve("staging")).isEmpty());
        assertEquals(
                List.of("docs", "paper.txt"),
                childNames(container.resolve("data")));
        assertFalse(tree(providerRoot).stream()
                .anyMatch(path -> path.startsWith("pending-")));
    }

    private static ProjectVersionSnapshot sourceSnapshot() {
        return new ProjectVersionSnapshot(
                SOURCE_VERSION,
                List.of(
                        sourceFile("paper.txt", PAPER),
                        sourceFile("docs/notes.md", NOTES)),
                Map.of("kind", "runtime-concurrency-test"));
    }

    private static ProjectFileSnapshot sourceFile(
            String path,
            byte[] content) {
        return new ProjectFileSnapshot(
                new ProjectPath(path),
                content,
                sha256(content),
                Map.of("mediaType", "text/plain"));
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(
                    "sha256",
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<Path> children(Path directory)
            throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.sorted().toList();
        }
    }

    private static List<String> childNames(Path directory)
            throws IOException {
        return children(directory).stream()
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
    }

    private static List<String> tree(Path root) throws IOException {
        try (var entries = Files.walk(root)) {
            return entries
                    .filter(path -> !path.equals(root))
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(
                        "timed out waiting for public source gate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                    "interrupted waiting for public source gate",
                    exception);
        }
    }

    private record SeededExecution(
            PersistedExecutionStartCommitted committed,
            LeaseRecord lease,
            PlanExecutionContextLeaseAttempt attempt) {
    }

    private record ConcurrentAttempt(
            PlanExecutionContextReady ready,
            RuntimeException failure) {
    }

    private record ConfirmObservation(
            PlanExecutionContextConfirmationRequest request,
            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    result) {
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (ZoneOffset.UTC.equals(zone)) {
                return this;
            }
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    private static final class TakeoverSignalingLeaseRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final String takeoverOwner;
        private final CountDownLatch takeoverApplied =
                new CountDownLatch(1);

        private TakeoverSignalingLeaseRepository(
                LeaseRepository delegate,
                String takeoverOwner) {
            this.delegate = delegate;
            this.takeoverOwner = takeoverOwner;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            PersistenceResult<LeaseRecord> result = delegate.acquire(
                    planId,
                    ownerId,
                    leaseToken,
                    expiresAt);
            if (takeoverOwner.equals(ownerId)
                    && (result.outcome()
                                    == PersistenceOutcome.APPLIED
                            || result.outcome()
                                    == PersistenceOutcome.REPLAYED)) {
                takeoverApplied.countDown();
            }
            return result;
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            return delegate.renew(planId, leaseToken, expiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                PlanId planId,
                String leaseToken) {
            return delegate.release(planId, leaseToken);
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId planId) {
            return delegate.find(planId);
        }

        private boolean awaitTakeover(long timeoutNanos)
                throws InterruptedException {
            return takeoverApplied.await(
                    timeoutNanos,
                    TimeUnit.NANOSECONDS);
        }
    }

    private static final class RecordingContextRepository
            implements PlanExecutionContextRepository {
        private final PlanExecutionContextRepository delegate;
        private final List<ConfirmObservation> confirmations =
                new CopyOnWriteArrayList<>();

        private RecordingContextRepository(
                PlanExecutionContextRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextReserved>
                reserve(PlanExecutionContextReservationRequest request) {
            return delegate.reserve(request);
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextConfirmed>
                confirm(PlanExecutionContextConfirmationRequest request) {
            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    result = delegate.confirm(request);
            confirmations.add(new ConfirmObservation(request, result));
            return result;
        }

        @Override
        public PersistenceResult<PlanExecutionContextSnapshot> inspect(
                PlanId planId) {
            return delegate.inspect(planId);
        }

        private List<ConfirmObservation> confirmations() {
            return List.copyOf(confirmations);
        }
    }

    private static final class FirstNoneGateRepository
            implements PlanExecutionContextRepository {
        private final PlanExecutionContextRepository delegate;
        private final CountDownLatch firstNoneArrivals;
        private final CountDownLatch release = new CountDownLatch(1);
        private final ThreadLocal<AtomicBoolean> firstInspection =
                ThreadLocal.withInitial(() -> new AtomicBoolean(true));
        private final AtomicInteger firstNoneObservations =
                new AtomicInteger();

        private FirstNoneGateRepository(
                PlanExecutionContextRepository delegate,
                int callers) {
            this.delegate = delegate;
            this.firstNoneArrivals = new CountDownLatch(callers);
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextReserved>
                reserve(PlanExecutionContextReservationRequest request) {
            return delegate.reserve(request);
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextConfirmed>
                confirm(PlanExecutionContextConfirmationRequest request) {
            return delegate.confirm(request);
        }

        @Override
        public PersistenceResult<PlanExecutionContextSnapshot> inspect(
                PlanId planId) {
            if (!firstInspection.get().compareAndSet(true, false)) {
                return delegate.inspect(planId);
            }
            PersistenceResult<PlanExecutionContextSnapshot> observed;
            try {
                observed = delegate.inspect(planId);
                assertEquals(
                        PersistenceOutcome.REJECTED,
                        observed.outcome());
                assertEquals(
                        PersistenceErrorCode.NOT_FOUND,
                        observed.failure().orElseThrow().code());
                assertEquals(
                        "planExecutionContext",
                        observed.failure().orElseThrow().path());
                firstNoneObservations.incrementAndGet();
            } finally {
                firstNoneArrivals.countDown();
            }
            awaitRelease();
            return observed;
        }

        private boolean awaitAll(long timeoutNanos)
                throws InterruptedException {
            return firstNoneArrivals.await(
                    timeoutNanos,
                    TimeUnit.NANOSECONDS);
        }

        private void release() {
            release.countDown();
        }

        private int firstNoneObservations() {
            return firstNoneObservations.get();
        }

        private void awaitRelease() {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "timed out waiting for NONE gate release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "interrupted waiting for NONE gate release",
                        exception);
            }
        }
    }

    private static final class CountingWorkspacePort
            implements WorkspacePort {
        private final WorkspacePort delegate;
        private final AtomicInteger materializeCalls =
                new AtomicInteger();
        private final AtomicInteger inspectCalls =
                new AtomicInteger();

        private CountingWorkspacePort(WorkspacePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public VerifiedWorkspaceMaterialization materialize(
                WorkspaceMaterializationSpec spec) {
            materializeCalls.incrementAndGet();
            return delegate.materialize(spec);
        }

        @Override
        public VerifiedWorkspaceMaterialization inspectMaterialization(
                WorkspaceMaterializationSpec spec) {
            inspectCalls.incrementAndGet();
            return delegate.inspectMaterialization(spec);
        }

        @Override
        public List<WorkspaceFileStat> list(WorkspaceRef workspace) {
            return delegate.list(workspace);
        }

        @Override
        public WorkspaceFileStat stat(
                WorkspaceRef workspace,
                ProjectPath path) {
            return delegate.stat(workspace, path);
        }

        @Override
        public byte[] read(
                WorkspaceRef workspace,
                ProjectPath path) {
            return delegate.read(workspace, path);
        }

        @Override
        public void create(
                WorkspaceRef workspace,
                ProjectPath path,
                byte[] content) {
            delegate.create(workspace, path, content);
        }

        @Override
        public void replace(
                WorkspaceRef workspace,
                ProjectPath path,
                byte[] content) {
            delegate.replace(workspace, path, content);
        }

        @Override
        public void delete(
                WorkspaceRef workspace,
                ProjectPath path) {
            delegate.delete(workspace, path);
        }

        @Override
        public void move(
                WorkspaceRef workspace,
                ProjectPath source,
                ProjectPath target) {
            delegate.move(workspace, source, target);
        }

        @Override
        public WorkspaceDiff diff(
                WorkspaceRef workspace,
                DiffId diffId,
                Instant createdAt) {
            return delegate.diff(workspace, diffId, createdAt);
        }

        @Override
        public void cleanup(WorkspaceRef workspace) {
            delegate.cleanup(workspace);
        }

        private int materializeCalls() {
            return materializeCalls.get();
        }

        private int interactionCalls() {
            return materializeCalls.get() + inspectCalls.get();
        }
    }
}
