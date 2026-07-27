package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
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
import io.paperagent.v2.workspace.WorkspaceErrorCode;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.T0;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.committed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionContextCompositionIntegrationTest {
    private static final String SUFFIX = "runtime-context-integration";
    private static final ProjectVersionRef SOURCE_VERSION =
            new ProjectVersionRef(
                    "runtime-context-project",
                    "runtime-context-version");
    private static final WorkspaceMaterializationSpec SPEC =
            new WorkspaceMaterializationSpec(
                    new WorkspaceId("runtime-context-workspace"),
                    SOURCE_VERSION,
                    new WorkspaceMaterializationLimits(
                            4096,
                            16384,
                            16));
    private static final byte[] PAPER =
            "runtime paper".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOTES =
            "runtime notes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path providerRoot;

    @Test
    void realSourceBackedCompositionPublishesAndReplaysWithoutNewOccupancy()
            throws Exception {
        AtomicInteger sourceLoads = new AtomicInteger();
        AtomicReference<ProjectVersionSnapshot> source =
                new AtomicReference<>(sourceSnapshot());
        LocalWorkspaceProvider realWorkspace = new LocalWorkspaceProvider(
                providerRoot,
                requested -> {
                    assertEquals(SOURCE_VERSION, requested);
                    sourceLoads.incrementAndGet();
                    return source.get();
                });
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        SeededExecution seeded = seedCommitted(persistence);
        var initialContext =
                persistence.planExecutionContexts().inspect(
                        seeded.committed().planId());
        assertEquals(PersistenceOutcome.REJECTED, initialContext.outcome());
        assertEquals(
                PersistenceErrorCode.NOT_FOUND,
                initialContext.failure().orElseThrow().code());

        CountingLeaseRepository leases =
                new CountingLeaseRepository(persistence.leases());
        CountingContextRepository contexts =
                new CountingContextRepository(
                        persistence.planExecutionContexts());
        CountingWorkspacePort workspace =
                new CountingWorkspacePort(realWorkspace);
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        leases,
                        workspace);
        PlanExecutionContextReady first = assertInstanceOf(
                PlanExecutionContextReady.class,
                composer.compose(new PlanExecutionContextCompositionRequest(
                        seeded.committed().planId(),
                        Optional.of(SPEC),
                        Optional.of(seeded.attempt()))));

        assertEquals(
                PlanExecutionContextCompositionResolution.CONFIRM_APPLIED,
                first.resolution());
        assertEquals(
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                first.leaseDisposition());
        assertExactAuthority(first, seeded.lease());
        PersistedPlanExecutionContextConfirmed stored =
                assertInstanceOf(
                        PersistedPlanExecutionContextConfirmed.class,
                        persistence.planExecutionContexts()
                                .inspect(seeded.committed().planId())
                                .value()
                                .orElseThrow());
        assertEquals(first.persistedContext(), stored);
        assertSame(
                first.verifiedWorkspace(),
                realWorkspace.inspectMaterialization(SPEC));
        assertEquals(
                first.persistedContext()
                        .sourceManifestFingerprint(),
                first.verifiedWorkspace()
                        .sourceManifestFingerprint());
        assertEquals(1, sourceLoads.get());
        assertPublishedShape(
                realWorkspace,
                first.verifiedWorkspace());
        List<String> firstTree = tree(providerRoot);
        int acquireCalls = leases.acquireCalls();
        int reserveCalls = contexts.reserveCalls();
        int confirmCalls = contexts.confirmCalls();
        int materializeCalls = workspace.materializeCalls();
        int loads = sourceLoads.get();

        PlanExecutionContextReady replay = assertInstanceOf(
                PlanExecutionContextReady.class,
                composer.compose(new PlanExecutionContextCompositionRequest(
                        seeded.committed().planId(),
                        Optional.empty(),
                        Optional.empty())));

        assertEquals(
                PlanExecutionContextCompositionResolution.OBSERVED_CONFIRMED,
                replay.resolution());
        assertEquals(
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                replay.leaseDisposition());
        assertEquals(first.persistedContext(), replay.persistedContext());
        assertSame(first.verifiedWorkspace(), replay.verifiedWorkspace());
        assertEquals(loads, sourceLoads.get());
        assertEquals(acquireCalls, leases.acquireCalls());
        assertEquals(reserveCalls, contexts.reserveCalls());
        assertEquals(confirmCalls, contexts.confirmCalls());
        assertEquals(materializeCalls, workspace.materializeCalls());
        assertEquals(firstTree, tree(providerRoot));
        assertPublishedShape(
                realWorkspace,
                replay.verifiedWorkspace());

        source.set(new ProjectVersionSnapshot(
                SOURCE_VERSION,
                List.of(sourceFile(
                        "drifted.txt",
                        "drift".getBytes(StandardCharsets.UTF_8))),
                Map.of("kind", "drifted-source")));
        byte[] edited = "edited runtime paper"
                .getBytes(StandardCharsets.UTF_8);
        realWorkspace.replace(
                replay.verifiedWorkspace().workspace(),
                new ProjectPath("paper.txt"),
                edited);
        PlanExecutionContextReady editedReplay = assertInstanceOf(
                PlanExecutionContextReady.class,
                composer.compose(new PlanExecutionContextCompositionRequest(
                        seeded.committed().planId(),
                        Optional.empty(),
                        Optional.empty())));
        assertEquals(
                PlanExecutionContextCompositionResolution.OBSERVED_CONFIRMED,
                editedReplay.resolution());
        assertArrayEquals(
                edited,
                realWorkspace.read(
                        editedReplay.verifiedWorkspace().workspace(),
                        new ProjectPath("paper.txt")));
        assertEquals(loads, sourceLoads.get());
        assertEquals(acquireCalls, leases.acquireCalls());
        assertEquals(reserveCalls, contexts.reserveCalls());
        assertEquals(confirmCalls, contexts.confirmCalls());
        assertEquals(materializeCalls, workspace.materializeCalls());
    }

    @Test
    void preReservedContextUsesActiveOrMaterializesMissingWorkspace()
            throws Exception {
        for (boolean preMaterialized : List.of(false, true)) {
            String suffix = preMaterialized
                    ? "pre-reserved-active"
                    : "pre-reserved-missing";
            Path root = providerRoot.resolve(suffix);
            Files.createDirectory(root);
            AtomicInteger sourceLoads = new AtomicInteger();
            LocalWorkspaceProvider realWorkspace =
                    new LocalWorkspaceProvider(
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
            SeededExecution seeded = seedCommitted(
                    persistence,
                    suffix,
                    Optional.of(SOURCE_VERSION));
            WorkspaceMaterializationSpec spec =
                    workspaceSpec(suffix);
            PersistedPlanExecutionContextReserved reserved =
                    reserve(persistence, seeded, spec);
            VerifiedWorkspaceMaterialization preexisting = null;
            if (preMaterialized) {
                preexisting = realWorkspace.materialize(spec);
            }
            DefaultPlanExecutionContextComposer composer =
                    new DefaultPlanExecutionContextComposer(
                            persistence.executionStartRecovery(),
                            persistence.planExecutionContexts(),
                            persistence.leases(),
                            workspace);

            PlanExecutionContextReady ready = assertInstanceOf(
                    PlanExecutionContextReady.class,
                    composer.compose(
                            new PlanExecutionContextCompositionRequest(
                                    seeded.committed().planId(),
                                    Optional.of(spec),
                                    Optional.of(seeded.attempt()))));

            assertEquals(reserved, ready.persistedContext().reservation());
            assertEquals(
                    seeded.lease().ownerId(),
                    ready.persistedContext().leaseOwnerId());
            assertEquals(
                    seeded.lease().fencingToken(),
                    ready.persistedContext().fencingToken());
            assertEquals(
                    preMaterialized ? 0 : 1,
                    workspace.materializeCalls());
            if (preexisting != null) {
                assertSame(preexisting, ready.verifiedWorkspace());
            }
            assertEquals(1, sourceLoads.get());
            assertEquals(
                    ready.verifiedWorkspace(),
                    realWorkspace.inspectMaterialization(spec));
        }
    }

    @Test
    void sourceLessCommittedExecutionDoesNotTouchWorkspace() {
        Path root = providerRoot.resolve("source-less");
        try {
            Files.createDirectory(root);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        AtomicInteger sourceLoads = new AtomicInteger();
        LocalWorkspaceProvider realWorkspace =
                new LocalWorkspaceProvider(
                        root,
                        ignored -> {
                            sourceLoads.incrementAndGet();
                            return sourceSnapshot();
                        });
        CountingWorkspacePort workspace =
                new CountingWorkspacePort(realWorkspace);
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        SeededExecution seeded = seedCommitted(
                persistence,
                "source-less",
                Optional.empty());
        CountingLeaseRepository leases =
                new CountingLeaseRepository(persistence.leases());
        CountingContextRepository contexts =
                new CountingContextRepository(
                        persistence.planExecutionContexts());
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        leases,
                        workspace);

        PlanExecutionContextNotRequired outcome = assertInstanceOf(
                PlanExecutionContextNotRequired.class,
                composer.compose(new PlanExecutionContextCompositionRequest(
                        seeded.committed().planId(),
                        Optional.empty(),
                        Optional.empty())));

        assertEquals(
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                outcome.leaseDisposition());
        assertEquals(0, leases.acquireCalls());
        assertEquals(0, contexts.reserveCalls());
        assertEquals(0, contexts.confirmCalls());
        assertEquals(0, workspace.materializeCalls());
        assertEquals(0, sourceLoads.get());
    }

    @Test
    void responseLossAfterEachMutationReconcilesFromRealAuthority() {
        Path root = providerRoot.resolve("response-loss");
        try {
            Files.createDirectory(root);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        AtomicInteger sourceLoads = new AtomicInteger();
        LocalWorkspaceProvider realWorkspace =
                new LocalWorkspaceProvider(
                        root,
                        requested -> {
                            assertEquals(SOURCE_VERSION, requested);
                            sourceLoads.incrementAndGet();
                            return sourceSnapshot();
                        });
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        SeededExecution seeded = seedCommitted(
                persistence,
                "response-loss",
                Optional.of(SOURCE_VERSION));
        WorkspaceMaterializationSpec spec =
                workspaceSpec("response-loss");
        ResponseLossContextRepository contexts =
                new ResponseLossContextRepository(
                        persistence.planExecutionContexts());
        ResponseLossWorkspacePort workspace =
                new ResponseLossWorkspacePort(realWorkspace);
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        contexts,
                        persistence.leases(),
                        workspace);

        PlanExecutionContextReady ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                composer.compose(new PlanExecutionContextCompositionRequest(
                        seeded.committed().planId(),
                        Optional.of(spec),
                        Optional.of(seeded.attempt()))));

        assertEquals(
                PlanExecutionContextCompositionResolution
                        .RECONCILED_AFTER_RESPONSE_LOSS,
                ready.resolution());
        assertEquals(1, contexts.reserveCalls());
        assertEquals(1, contexts.confirmCalls());
        assertEquals(1, workspace.materializeCalls());
        assertEquals(1, sourceLoads.get());
        assertEquals(
                ready.persistedContext(),
                persistence.planExecutionContexts()
                        .inspect(seeded.committed().planId())
                        .value()
                        .orElseThrow());
        assertEquals(
                ready.verifiedWorkspace(),
                realWorkspace.inspectMaterialization(spec));
    }

    @Test
    void confirmedWorkspaceMissingRetiredOrPartialFailsClosed()
            throws Exception {
        for (WorkspaceFailureMode mode :
                WorkspaceFailureMode.values()) {
            String suffix = "confirmed-" + mode.name().toLowerCase();
            Path root = providerRoot.resolve(suffix);
            Files.createDirectory(root);
            LocalWorkspaceProvider workspace =
                    new LocalWorkspaceProvider(
                            root,
                            requested -> {
                                assertEquals(SOURCE_VERSION, requested);
                                return sourceSnapshot();
                            });
            InMemoryPersistence persistence = new InMemoryPersistence(
                    Clock.fixed(T0, ZoneOffset.UTC));
            SeededExecution seeded = seedCommitted(
                    persistence,
                    suffix,
                    Optional.of(SOURCE_VERSION));
            WorkspaceMaterializationSpec spec =
                    workspaceSpec(suffix);
            reserve(persistence, seeded, spec);
            VerifiedWorkspaceMaterialization verified;
            if (mode == WorkspaceFailureMode.MISSING) {
                verified = new VerifiedWorkspaceMaterialization(
                        spec,
                        sha256("missing".getBytes(StandardCharsets.UTF_8)));
            } else {
                verified = workspace.materialize(spec);
            }
            confirm(persistence, seeded, spec, verified);
            if (mode == WorkspaceFailureMode.RETIRED) {
                workspace.cleanup(verified.workspace());
            } else if (mode == WorkspaceFailureMode.PARTIAL) {
                Path container = children(root).get(0);
                Files.writeString(
                        container.resolve("staging/leftover.tmp"),
                        "leftover");
            }
            DefaultPlanExecutionContextComposer composer =
                    new DefaultPlanExecutionContextComposer(
                            persistence.executionStartRecovery(),
                            persistence.planExecutionContexts(),
                            persistence.leases(),
                            workspace);

            PlanExecutionContextWorkspaceRejected rejected =
                    assertInstanceOf(
                            PlanExecutionContextWorkspaceRejected.class,
                            composer.compose(
                                    new PlanExecutionContextCompositionRequest(
                                            seeded.committed().planId(),
                                            Optional.empty(),
                                            Optional.empty())));

            assertEquals(
                    mode.errorCode(),
                    rejected.workspaceErrorCode());
            assertEquals(
                    PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                    rejected.stage());
            assertEquals(
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                    rejected.leaseDisposition());
        }
    }

    @Test
    void expiredLeaseTakeoverConfirmsWithNewFenceAndPermanentReservation()
            throws Exception {
        MutableClock clock = new MutableClock(T0);
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        SeededExecution seeded = seedCommitted(
                persistence,
                "lease-takeover",
                Optional.of(SOURCE_VERSION));
        WorkspaceMaterializationSpec spec =
                workspaceSpec("lease-takeover");
        PersistedPlanExecutionContextReserved reservation =
                reserve(persistence, seeded, spec);
        clock.set(T0.plusSeconds(601));
        PlanExecutionContextLeaseAttempt takeover =
                new PlanExecutionContextLeaseAttempt(
                        "takeover-owner",
                        "takeover-token",
                        T0.plusSeconds(1200));
        Path root = providerRoot.resolve("lease-takeover");
        Files.createDirectory(root);
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider workspace =
                new LocalWorkspaceProvider(
                        root,
                        requested -> {
                            assertEquals(SOURCE_VERSION, requested);
                            loads.incrementAndGet();
                            return sourceSnapshot();
                        });
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        persistence.executionStartRecovery(),
                        persistence.planExecutionContexts(),
                        persistence.leases(),
                        workspace);

        PlanExecutionContextReady ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                composer.compose(new PlanExecutionContextCompositionRequest(
                        seeded.committed().planId(),
                        Optional.of(spec),
                        Optional.of(takeover))));

        assertEquals(reservation, ready.persistedContext().reservation());
        assertEquals(
                seeded.lease().fencingToken(),
                reservation.fencingToken());
        assertEquals(
                "takeover-owner",
                ready.persistedContext().leaseOwnerId());
        assertTrue(
                ready.persistedContext().fencingToken()
                        > reservation.fencingToken());
        assertEquals(1, loads.get());
    }

    private static SeededExecution seedCommitted(
            InMemoryPersistence persistence) {
        return seedCommitted(
                persistence,
                SUFFIX,
                Optional.of(SOURCE_VERSION));
    }

    private static SeededExecution seedCommitted(
            InMemoryPersistence persistence,
            String suffix,
            Optional<ProjectVersionRef> sourceVersion) {
        PersistedExecutionStartCommitted template = committed(
                suffix,
                sourceVersion);
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
        String owner = "runtime-context-owner-" + suffix;
        String token = "runtime-context-token-" + suffix;
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
        assertEquals(
                PersistenceOutcome.FOUND,
                persistence.executionStartRecovery()
                        .inspect(template.planId())
                        .outcome());
        return new SeededExecution(
                committed,
                lease,
                new PlanExecutionContextLeaseAttempt(
                        owner,
                        token,
                        expiresAt));
    }

    private static void assertExactAuthority(
            PlanExecutionContextReady ready,
            LeaseRecord lease) {
        PersistedPlanExecutionContextConfirmed confirmed =
                ready.persistedContext();
        assertEquals(SPEC, confirmed.materializationSpec());
        assertEquals(lease.ownerId(), confirmed.reservation().leaseOwnerId());
        assertEquals(
                lease.fencingToken(),
                confirmed.reservation().fencingToken());
        assertEquals(lease.ownerId(), confirmed.leaseOwnerId());
        assertEquals(
                lease.fencingToken(),
                confirmed.fencingToken());
        assertEquals(
                ready.verifiedWorkspace()
                        .sourceManifestFingerprint(),
                confirmed.sourceManifestFingerprint());
    }

    private static WorkspaceMaterializationSpec workspaceSpec(
            String suffix) {
        return new WorkspaceMaterializationSpec(
                new WorkspaceId("runtime-workspace-" + suffix),
                SOURCE_VERSION,
                SPEC.limits());
    }

    private static PersistedPlanExecutionContextReserved reserve(
            InMemoryPersistence persistence,
            SeededExecution seeded,
            WorkspaceMaterializationSpec spec) {
        var revision = seeded.committed()
                .currentPlan()
                .latestRevision();
        var start = seeded.committed().executionStart();
        PersistenceResult<PersistedPlanExecutionContextReserved> result =
                persistence.planExecutionContexts()
                        .reserve(
                                new PlanExecutionContextReservationRequest(
                                        seeded.committed().planId(),
                                        seeded.attempt().leaseToken(),
                                        seeded.lease().fencingToken(),
                                        revision.id(),
                                        revision.number(),
                                        start.startedCheckpoint().version(),
                                        start.startEvent().sequence(),
                                        spec));
        assertEquals(PersistenceOutcome.APPLIED, result.outcome());
        return result.value().orElseThrow();
    }

    private static PersistedPlanExecutionContextConfirmed confirm(
            InMemoryPersistence persistence,
            SeededExecution seeded,
            WorkspaceMaterializationSpec spec,
            VerifiedWorkspaceMaterialization verified) {
        PersistenceResult<PersistedPlanExecutionContextConfirmed> result =
                persistence.planExecutionContexts()
                        .confirm(
                                new PlanExecutionContextConfirmationRequest(
                                        seeded.committed().planId(),
                                        seeded.attempt().leaseToken(),
                                        seeded.lease().fencingToken(),
                                        spec,
                                        verified.sourceManifestFingerprint()));
        assertEquals(PersistenceOutcome.APPLIED, result.outcome());
        return result.value().orElseThrow();
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
        assertEquals(
                List.of("data", "staging"),
                childNames(container));
        Path data = container.resolve("data");
        Path staging = container.resolve("staging");
        assertEquals(List.of("docs", "paper.txt"), childNames(data));
        assertEquals(List.of("notes.md"), childNames(data.resolve("docs")));
        assertTrue(childNames(staging).isEmpty());
        assertArrayEquals(PAPER, Files.readAllBytes(data.resolve("paper.txt")));
        assertArrayEquals(
                NOTES,
                Files.readAllBytes(data.resolve("docs/notes.md")));
        assertFalse(tree(providerRoot).stream()
                .anyMatch(path -> path.startsWith("pending-")));
    }

    private static ProjectVersionSnapshot sourceSnapshot() {
        return new ProjectVersionSnapshot(
                SOURCE_VERSION,
                List.of(
                        sourceFile("paper.txt", PAPER),
                        sourceFile("docs/notes.md", NOTES)),
                Map.of("kind", "runtime-context-test"));
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

    private record SeededExecution(
            PersistedExecutionStartCommitted committed,
            LeaseRecord lease,
            PlanExecutionContextLeaseAttempt attempt) {
    }

    private enum WorkspaceFailureMode {
        MISSING(WorkspaceErrorCode.WORKSPACE_NOT_FOUND),
        RETIRED(WorkspaceErrorCode.WORKSPACE_RETIRED),
        PARTIAL(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE);

        private final WorkspaceErrorCode errorCode;

        WorkspaceFailureMode(WorkspaceErrorCode errorCode) {
            this.errorCode = errorCode;
        }

        private WorkspaceErrorCode errorCode() {
            return errorCode;
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
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

    private static final class ResponseLossContextRepository
            implements PlanExecutionContextRepository {
        private final PlanExecutionContextRepository delegate;
        private final AtomicBoolean loseReserve = new AtomicBoolean(true);
        private final AtomicBoolean loseConfirm = new AtomicBoolean(true);
        private final AtomicInteger reserveCalls = new AtomicInteger();
        private final AtomicInteger confirmCalls = new AtomicInteger();

        private ResponseLossContextRepository(
                PlanExecutionContextRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextReserved>
                reserve(PlanExecutionContextReservationRequest request) {
            reserveCalls.incrementAndGet();
            PersistenceResult<PersistedPlanExecutionContextReserved>
                    result = delegate.reserve(request);
            if (loseReserve.getAndSet(false)) {
                throw new IllegalStateException(
                        "reserve response lost after delegate");
            }
            return result;
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextConfirmed>
                confirm(PlanExecutionContextConfirmationRequest request) {
            confirmCalls.incrementAndGet();
            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    result = delegate.confirm(request);
            if (loseConfirm.getAndSet(false)) {
                throw new IllegalStateException(
                        "confirm response lost after delegate");
            }
            return result;
        }

        @Override
        public PersistenceResult<PlanExecutionContextSnapshot> inspect(
                io.paperagent.v2.contracts.PlanId planId) {
            return delegate.inspect(planId);
        }

        private int reserveCalls() {
            return reserveCalls.get();
        }

        private int confirmCalls() {
            return confirmCalls.get();
        }
    }

    private static final class ResponseLossWorkspacePort
            implements WorkspacePort {
        private final WorkspacePort delegate;
        private final AtomicBoolean loseMaterialize =
                new AtomicBoolean(true);
        private final AtomicInteger materializeCalls =
                new AtomicInteger();

        private ResponseLossWorkspacePort(WorkspacePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public VerifiedWorkspaceMaterialization materialize(
                WorkspaceMaterializationSpec spec) {
            materializeCalls.incrementAndGet();
            VerifiedWorkspaceMaterialization result =
                    delegate.materialize(spec);
            if (loseMaterialize.getAndSet(false)) {
                throw new IllegalStateException(
                        "materialize response lost after delegate");
            }
            return result;
        }

        @Override
        public VerifiedWorkspaceMaterialization inspectMaterialization(
                WorkspaceMaterializationSpec spec) {
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
    }

    private static final class CountingLeaseRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final AtomicInteger acquireCalls = new AtomicInteger();

        private CountingLeaseRepository(LeaseRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                io.paperagent.v2.contracts.PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            acquireCalls.incrementAndGet();
            return delegate.acquire(
                    planId,
                    ownerId,
                    leaseToken,
                    expiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            return delegate.renew(planId, leaseToken, expiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken) {
            return delegate.release(planId, leaseToken);
        }

        @Override
        public PersistenceResult<LeaseRecord> find(
                io.paperagent.v2.contracts.PlanId planId) {
            return delegate.find(planId);
        }

        private int acquireCalls() {
            return acquireCalls.get();
        }
    }

    private static final class CountingContextRepository
            implements PlanExecutionContextRepository {
        private final PlanExecutionContextRepository delegate;
        private final AtomicInteger reserveCalls = new AtomicInteger();
        private final AtomicInteger confirmCalls = new AtomicInteger();

        private CountingContextRepository(
                PlanExecutionContextRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextReserved>
                reserve(PlanExecutionContextReservationRequest request) {
            reserveCalls.incrementAndGet();
            return delegate.reserve(request);
        }

        @Override
        public PersistenceResult<PersistedPlanExecutionContextConfirmed>
                confirm(PlanExecutionContextConfirmationRequest request) {
            confirmCalls.incrementAndGet();
            return delegate.confirm(request);
        }

        @Override
        public PersistenceResult<PlanExecutionContextSnapshot> inspect(
                io.paperagent.v2.contracts.PlanId planId) {
            return delegate.inspect(planId);
        }

        private int reserveCalls() {
            return reserveCalls.get();
        }

        private int confirmCalls() {
            return confirmCalls.get();
        }
    }

    private static final class CountingWorkspacePort
            implements WorkspacePort {
        private final WorkspacePort delegate;
        private final AtomicInteger materializeCalls =
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
    }
}
