package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMaterializationConcurrencyTest {
    @TempDir
    Path root;

    @Test
    void concurrentProvidersUseDistinctOwnedCaseProbeDirectories()
            throws Exception {
        Path sharedRoot = root.resolve("shared-case-probe");
        CountDownLatch bothLowerFilesCreated = new CountDownLatch(2);
        CountDownLatch releaseProbes = new CountDownLatch(1);
        List<Path> observedProbeDirectories =
                java.util.Collections.synchronizedList(new ArrayList<>());
        LocalWorkspaceProvider.WorkspaceCaseProbeObserver observer =
                probeDirectory -> {
                    observedProbeDirectories.add(probeDirectory);
                    bothLowerFilesCreated.countDown();
                    await(releaseProbes);
                };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<LocalWorkspaceProvider> first = executor.submit(
                () -> new LocalWorkspaceProvider(
                        sharedRoot,
                        ignored -> snapshot(),
                        observer));
        Future<LocalWorkspaceProvider> second = executor.submit(
                () -> new LocalWorkspaceProvider(
                        sharedRoot,
                        ignored -> snapshot(),
                        observer));
        try {
            await(bothLowerFilesCreated);
            releaseProbes.countDown();
            assertNotNull(first.get());
            assertNotNull(second.get());
        } finally {
            releaseProbes.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(
                2,
                observedProbeDirectories.stream().distinct().count());
        try (var entries = Files.list(sharedRoot)) {
            assertEquals(
                    0,
                    entries.filter(path -> path.getFileName().toString()
                            .startsWith(".paperagent-case-probe-"))
                            .count());
        }
    }

    @Test
    void thirtyTwoConcurrentExactCallsLoadCopyAndPublishOnlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> {
                    loads.incrementAndGet();
                    return snapshot(file("paper.txt", "paper"));
                },
                (target, content, options) -> {
                    writes.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = spec("concurrent-replay");
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Callable<VerifiedWorkspaceMaterialization>> calls = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                calls.add(() -> provider.materialize(spec));
            }
            List<Future<VerifiedWorkspaceMaterialization>> futures =
                    executor.invokeAll(calls);
            VerifiedWorkspaceMaterialization first = futures.get(0).get();
            for (Future<VerifiedWorkspaceMaterialization> future : futures) {
                assertEquals(first, future.get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, loads.get());
        assertEquals(1, writes.get());
        assertEquals(1, publishes.get());
    }

    @Test
    void competingProvidersNeverDeleteWinnerFinalTree() throws Exception {
        Path sharedRoot = root.resolve("shared");
        Files.createDirectories(sharedRoot);
        CountDownLatch bothLoaded = new CountDownLatch(2);
        CountDownLatch releaseLoads = new CountDownLatch(1);
        ProjectVersionSource source = ignored -> {
            bothLoaded.countDown();
            try {
                if (!releaseLoads.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for competing source load");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
            return snapshot(file("paper.txt", "paper"));
        };
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicBoolean everyPublishSawAbsentTarget = new AtomicBoolean(true);
        WorkspaceMaterializationWriter writer = (target, content, options) -> {
            writes.incrementAndGet();
            Files.write(target, content, options);
        };
        WorkspaceDirectoryPublisher publisher = (pending, target) -> {
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                everyPublishSawAbsentTarget.set(false);
            }
            publishes.incrementAndGet();
            LocalWorkspaceProvider.defaultPublish(pending, target);
        };
        LocalWorkspaceProvider first = new LocalWorkspaceProvider(
                sharedRoot,
                source,
                writer,
                publisher,
                LocalWorkspaceProvider::deleteTree);
        LocalWorkspaceProvider second = new LocalWorkspaceProvider(
                sharedRoot,
                source,
                writer,
                publisher,
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = spec("provider-race");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> firstAttempt =
                    executor.submit(() -> attempt(first, spec));
            Future<Attempt> secondAttempt =
                    executor.submit(() -> attempt(second, spec));
            assertTrue(bothLoaded.await(5, TimeUnit.SECONDS));
            releaseLoads.countDown();
            Attempt left = firstAttempt.get();
            Attempt right = secondAttempt.get();

            List<Attempt> successes = List.of(left, right).stream()
                    .filter(attempt -> attempt.result() != null)
                    .toList();
            List<Attempt> failures = List.of(left, right).stream()
                    .filter(attempt -> attempt.failure() != null)
                    .toList();
            assertEquals(1, successes.size());
            assertEquals(1, failures.size());
            assertEquals(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    failures.get(0).failure().code());
            assertEquals(1, writes.get());
            assertEquals(1, publishes.get());
            assertTrue(everyPublishSawAbsentTarget.get());
            Attempt winner = successes.get(0);
            assertNotNull(winner.result());
            assertBytes(
                    "paper",
                    winner.provider().read(
                            winner.result().workspace(),
                            new ProjectPath("paper.txt")));
            Path finalTree = sharedRoot.resolve(
                    "ws-" + WorkspaceHashes.sha256Text(spec.workspaceId().value()));
            assertTrue(Files.isDirectory(finalTree));
            assertEquals("paper", Files.readString(finalTree.resolve("data/paper.txt")));
        } finally {
            releaseLoads.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void delayedSourceCannotAcquirePendingWhilePublishedWinnerStillHoldsClaim()
            throws Exception {
        Path sharedRoot = root.resolve("delayed-shared");
        Files.createDirectories(sharedRoot);
        CountDownLatch delayedSourceEntered = new CountDownLatch(1);
        CountDownLatch releaseDelayedSource = new CountDownLatch(1);
        CountDownLatch winnerPublished = new CountDownLatch(1);
        CountDownLatch releasePublisherReturn = new CountDownLatch(1);
        AtomicInteger delayedWrites = new AtomicInteger();
        AtomicInteger delayedPublishes = new AtomicInteger();
        ProjectVersionSource delayedSource = ignored -> {
            delayedSourceEntered.countDown();
            await(releaseDelayedSource);
            return snapshot(file("paper.txt", "winner"));
        };
        LocalWorkspaceProvider delayed = new LocalWorkspaceProvider(
                sharedRoot,
                delayedSource,
                (target, content, options) -> {
                    delayedWrites.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    delayedPublishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);
        LocalWorkspaceProvider winner = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> snapshot(file("paper.txt", "winner")),
                Files::write,
                (pending, target) -> {
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                    winnerPublished.countDown();
                    await(releasePublisherReturn);
                },
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = spec("delayed-source-race");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> delayedAttempt =
                    executor.submit(() -> attempt(delayed, spec));
            assertTrue(delayedSourceEntered.await(5, TimeUnit.SECONDS));
            Future<Attempt> winnerAttempt =
                    executor.submit(() -> attempt(winner, spec));
            assertTrue(winnerPublished.await(5, TimeUnit.SECONDS));

            releaseDelayedSource.countDown();
            Attempt delayedResult = delayedAttempt.get();
            assertEquals(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    delayedResult.failure().code());
            assertEquals(0, delayedWrites.get());
            assertEquals(0, delayedPublishes.get());
            Path pending = sharedRoot.resolve(
                    "pending-" + WorkspaceHashes.sha256Text(spec.workspaceId().value()));
            Path target = sharedRoot.resolve(
                    "ws-" + WorkspaceHashes.sha256Text(spec.workspaceId().value()));
            assertTrue(Files.notExists(pending));
            assertEquals("winner", Files.readString(target.resolve("data/paper.txt")));

            releasePublisherReturn.countDown();
            Attempt winnerResult = winnerAttempt.get();
            assertNotNull(winnerResult.result());
            assertEquals("winner", Files.readString(target.resolve("data/paper.txt")));
        } finally {
            releaseDelayedSource.countDown();
            releasePublisherReturn.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void visibleClaimBlocksMaterializeAndInspectBeforeAnySourceOrCopyWork()
            throws Exception {
        Path sharedRoot = root.resolve("claim-only-shared");
        Files.createDirectories(sharedRoot);
        CountDownLatch claimHolderWriterEntered = new CountDownLatch(1);
        CountDownLatch releaseClaimHolder = new CountDownLatch(1);
        WorkspaceMaterializationSpec spec = spec("claim-only");
        LocalWorkspaceProvider claimHolder = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> snapshot(file("paper.txt", "paper")),
                (target, content, options) -> {
                    LocalWorkspaceProvider.deleteTree(target.getParent().getParent());
                    claimHolderWriterEntered.countDown();
                    await(releaseClaimHolder);
                },
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree);
        AtomicInteger blockedLoads = new AtomicInteger();
        AtomicInteger blockedWrites = new AtomicInteger();
        AtomicInteger blockedPublishes = new AtomicInteger();
        LocalWorkspaceProvider blocked = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> {
                    blockedLoads.incrementAndGet();
                    return snapshot(file("paper.txt", "paper"));
                },
                (target, content, options) -> {
                    blockedWrites.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    blockedPublishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Attempt> holderAttempt =
                    executor.submit(() -> attempt(claimHolder, spec));
            assertTrue(claimHolderWriterEntered.await(5, TimeUnit.SECONDS));

            Attempt blockedAttempt = attempt(blocked, spec);
            assertEquals(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    blockedAttempt.failure().code());
            WorkspaceException inspectFailure = org.junit.jupiter.api.Assertions.assertThrows(
                    WorkspaceException.class,
                    () -> blocked.inspectMaterialization(spec));
            assertEquals(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    inspectFailure.code());
            assertEquals(0, blockedLoads.get());
            assertEquals(0, blockedWrites.get());
            assertEquals(0, blockedPublishes.get());

            releaseClaimHolder.countDown();
            assertEquals(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    holderAttempt.get().failure().code());

            VerifiedWorkspaceMaterialization retry = blocked.materialize(spec);
            assertNotNull(retry);
            assertEquals(1, blockedLoads.get());
            assertEquals(1, blockedWrites.get());
            assertEquals(1, blockedPublishes.get());
        } finally {
            releaseClaimHolder.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeCleanupClaimBlocksCompetingProviderUntilSharedRetirement()
            throws Exception {
        Path sharedRoot = root.resolve("active-cleanup-shared");
        Files.createDirectories(sharedRoot);
        CountDownLatch finalDeleted = new CountDownLatch(1);
        CountDownLatch releaseDeleteReturn = new CountDownLatch(1);
        AtomicInteger ownerDeletes = new AtomicInteger();
        LocalWorkspaceProvider owner = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> snapshot(file("paper.txt", "paper")),
                Files::write,
                LocalWorkspaceProvider::defaultPublish,
                target -> {
                    ownerDeletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                    finalDeleted.countDown();
                    await(releaseDeleteReturn);
                });
        WorkspaceMaterializationSpec spec = spec("active-cleanup-claim");
        WorkspaceRef workspace = owner.materialize(spec).workspace();
        AtomicInteger observerLoads = new AtomicInteger();
        AtomicInteger observerWrites = new AtomicInteger();
        AtomicInteger observerPublishes = new AtomicInteger();
        AtomicInteger observerDeletes = new AtomicInteger();
        LocalWorkspaceProvider observer = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> {
                    observerLoads.incrementAndGet();
                    return snapshot(file("paper.txt", "paper"));
                },
                (target, content, options) -> {
                    observerWrites.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    observerPublishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                target -> {
                    observerDeletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Void> cleanup = executor.submit(() -> {
                owner.cleanup(workspace);
                return null;
            });
            assertTrue(finalDeleted.await(5, TimeUnit.SECONDS));

            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.materialize(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.inspectMaterialization(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.list(workspace));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.cleanup(workspace));
            assertEquals(0, observerLoads.get());
            assertEquals(0, observerWrites.get());
            assertEquals(0, observerPublishes.get());
            assertEquals(0, observerDeletes.get());

            releaseDeleteReturn.countDown();
            cleanup.get();

            assertCode(
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    () -> observer.materialize(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    () -> observer.inspectMaterialization(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    () -> observer.list(workspace));
            observer.cleanup(workspace);
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH,
                    () -> observer.cleanup(new WorkspaceRef(
                            workspace.id(),
                            new ProjectVersionRef("project-1", "wrong-version"))));
            assertEquals(1, ownerDeletes.get());
            assertEquals(0, observerLoads.get());
            assertEquals(0, observerWrites.get());
            assertEquals(0, observerPublishes.get());
            assertEquals(0, observerDeletes.get());
            assertFalse(Files.exists(
                    sharedRoot.resolve(
                            "pending-" + WorkspaceHashes.sha256Text(
                                    spec.workspaceId().value()))));
            assertFalse(Files.exists(
                    sharedRoot.resolve(
                            "ws-" + WorkspaceHashes.sha256Text(
                                    spec.workspaceId().value()))));
        } finally {
            releaseDeleteReturn.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupFailureAfterDeletingFinalRetainsClaimUntilExactRetryRetires()
            throws Exception {
        Path sharedRoot = root.resolve("failed-cleanup-shared");
        Files.createDirectories(sharedRoot);
        CountDownLatch finalDeleted = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        AtomicInteger ownerDeletes = new AtomicInteger();
        LocalWorkspaceProvider owner = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> snapshot(file("paper.txt", "paper")),
                Files::write,
                LocalWorkspaceProvider::defaultPublish,
                target -> {
                    ownerDeletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                    finalDeleted.countDown();
                    await(releaseFailure);
                    throw new IOException("forced failure after delete");
                });
        WorkspaceMaterializationSpec spec = spec("failed-cleanup-claim");
        WorkspaceRef workspace = owner.materialize(spec).workspace();
        AtomicInteger observerLoads = new AtomicInteger();
        AtomicInteger observerWrites = new AtomicInteger();
        AtomicInteger observerPublishes = new AtomicInteger();
        AtomicInteger observerDeletes = new AtomicInteger();
        LocalWorkspaceProvider observer = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> {
                    observerLoads.incrementAndGet();
                    return snapshot(file("paper.txt", "paper"));
                },
                (target, content, options) -> {
                    observerWrites.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    observerPublishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                target -> {
                    observerDeletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkspaceException> cleanup = executor.submit(() -> {
                try {
                    owner.cleanup(workspace);
                    return null;
                } catch (WorkspaceException exception) {
                    return exception;
                }
            });
            assertTrue(finalDeleted.await(5, TimeUnit.SECONDS));

            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.materialize(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.inspectMaterialization(spec));
            assertEquals(0, observerLoads.get());
            assertEquals(0, observerWrites.get());
            assertEquals(0, observerPublishes.get());
            assertEquals(0, observerDeletes.get());

            releaseFailure.countDown();
            WorkspaceException failure = cleanup.get();
            assertNotNull(failure);
            assertEquals(WorkspaceErrorCode.IO_FAILURE, failure.code());

            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.materialize(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.inspectMaterialization(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> observer.list(workspace));
            assertEquals(0, observerLoads.get());
            assertEquals(0, observerDeletes.get());

            owner.cleanup(workspace);

            assertEquals(1, ownerDeletes.get());
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    () -> observer.materialize(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    () -> observer.inspectMaterialization(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    () -> observer.list(workspace));
            assertEquals(0, observerLoads.get());
            assertEquals(0, observerWrites.get());
            assertEquals(0, observerPublishes.get());
            assertEquals(0, observerDeletes.get());
        } finally {
            releaseFailure.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedMaterializationCleanupPendingRetainsClaimUntilSharedRetirement()
            throws Exception {
        Path sharedRoot = root.resolve("failed-materialization-shared");
        Files.createDirectories(sharedRoot);
        AtomicInteger ownerDeletes = new AtomicInteger();
        LocalWorkspaceProvider owner = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> snapshot(file("paper.txt", "paper")),
                (target, content, options) -> {
                    throw new IOException("forced copy failure");
                },
                LocalWorkspaceProvider::defaultPublish,
                target -> ownerDeletes.incrementAndGet());
        WorkspaceMaterializationSpec spec =
                spec("failed-materialization-retained-claim");
        WorkspaceRef workspace = new WorkspaceRef(
                spec.workspaceId(),
                spec.sourceProjectVersion());

        assertCode(
                WorkspaceErrorCode.IO_FAILURE,
                () -> owner.materialize(spec));
        assertEquals(1, ownerDeletes.get());
        Path pending = sharedRoot.resolve(
                "pending-" + WorkspaceHashes.sha256Text(
                        spec.workspaceId().value()));
        assertTrue(Files.isDirectory(pending));

        LocalWorkspaceProvider.deleteTree(pending);
        assertFalse(Files.exists(pending));

        AtomicInteger observerLoads = new AtomicInteger();
        AtomicInteger observerWrites = new AtomicInteger();
        AtomicInteger observerPublishes = new AtomicInteger();
        AtomicInteger observerDeletes = new AtomicInteger();
        LocalWorkspaceProvider observer = new LocalWorkspaceProvider(
                sharedRoot,
                ignored -> {
                    observerLoads.incrementAndGet();
                    return snapshot(file("paper.txt", "paper"));
                },
                (target, content, options) -> {
                    observerWrites.incrementAndGet();
                    Files.write(target, content, options);
                },
                (staging, target) -> {
                    observerPublishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(staging, target);
                },
                target -> {
                    observerDeletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });

        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> observer.materialize(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> observer.inspectMaterialization(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> observer.list(workspace));
        assertEquals(0, observerLoads.get());
        assertEquals(0, observerWrites.get());
        assertEquals(0, observerPublishes.get());
        assertEquals(0, observerDeletes.get());

        owner.cleanup(workspace);

        assertEquals(1, ownerDeletes.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> observer.materialize(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> observer.inspectMaterialization(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> observer.list(workspace));
        observer.cleanup(workspace);
        assertEquals(0, observerLoads.get());
        assertEquals(0, observerWrites.get());
        assertEquals(0, observerPublishes.get());
        assertEquals(0, observerDeletes.get());
    }

    private static Attempt attempt(
            LocalWorkspaceProvider provider,
            WorkspaceMaterializationSpec spec) {
        try {
            return new Attempt(provider, provider.materialize(spec), null);
        } catch (WorkspaceException exception) {
            return new Attempt(provider, null, exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }

    private static void assertCode(
            WorkspaceErrorCode expected,
            Runnable operation) {
        WorkspaceException exception =
                assertThrows(WorkspaceException.class, operation::run);
        assertEquals(expected, exception.code());
    }

    private record Attempt(
            LocalWorkspaceProvider provider,
            VerifiedWorkspaceMaterialization result,
            WorkspaceException failure) {
    }
}
