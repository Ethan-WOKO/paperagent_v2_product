package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.GENEROUS_LIMITS;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMaterializationRestartRecoveryTest {
    @TempDir
    Path root;

    @Test
    void providerRecreationAdoptsExactPublishedCutAndRestoresOperations()
            throws Exception {
        Path providerRoot = root.resolve("successful-adoption");
        ProjectVersionSnapshot source = snapshot(
                file("paper.txt", "paper"),
                file("notes/original.txt", "original"));
        WorkspaceMaterializationSpec materializationSpec = spec("restart-success");
        VerifiedWorkspaceMaterialization published =
                new LocalWorkspaceProvider(providerRoot, ignored -> source)
                        .materialize(materializationSpec);
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider recovered = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> {
                    loads.incrementAndGet();
                    return source;
                });

        VerifiedWorkspaceMaterialization adopted =
                recovered.inspectMaterialization(materializationSpec);
        assertEquals(published, adopted);
        assertSame(adopted, recovered.inspectMaterialization(materializationSpec));
        assertEquals(1, loads.get());
        assertEquals(2, recovered.list(adopted.workspace()).size());
        assertEquals(
                file("paper.txt", "paper").hash(),
                recovered.stat(
                        adopted.workspace(),
                        new ProjectPath("paper.txt")).hash());
        assertBytes(
                "paper",
                recovered.read(adopted.workspace(), new ProjectPath("paper.txt")));

        recovered.replace(
                adopted.workspace(),
                new ProjectPath("paper.txt"),
                "edited".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        recovered.create(
                adopted.workspace(),
                new ProjectPath("created.txt"),
                "created".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        recovered.move(
                adopted.workspace(),
                new ProjectPath("notes/original.txt"),
                new ProjectPath("notes/moved.txt"));
        recovered.delete(
                adopted.workspace(),
                new ProjectPath("created.txt"));
        assertEquals(
                2,
                recovered.diff(
                        adopted.workspace(),
                        new DiffId("restart-diff"),
                        Instant.parse("2026-07-27T00:00:00Z"))
                        .entries()
                        .size());
        assertBytes(
                "edited",
                recovered.read(adopted.workspace(), new ProjectPath("paper.txt")));
        assertBytes(
                "original",
                recovered.read(adopted.workspace(), new ProjectPath("notes/moved.txt")));
        assertCode(
                WorkspaceErrorCode.PATH_NOT_FOUND,
                () -> recovered.stat(
                        adopted.workspace(),
                        new ProjectPath("notes/original.txt")));

        WorkspaceMaterializationSpec changedSpec =
                new WorkspaceMaterializationSpec(
                        materializationSpec.workspaceId(),
                        materializationSpec.sourceProjectVersion(),
                        new WorkspaceMaterializationLimits(
                                GENEROUS_LIMITS.maxFileBytes(),
                                GENEROUS_LIMITS.maxAggregateBytes(),
                                GENEROUS_LIMITS.maxFiles() - 1));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
                () -> recovered.inspectMaterialization(changedSpec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH,
                () -> recovered.list(new WorkspaceRef(
                        materializationSpec.workspaceId(),
                        new ProjectVersionRef("project-1", "changed-version"))));
        assertEquals(1, loads.get());

        recovered.cleanup(adopted.workspace());
        assertFalse(Files.exists(container(providerRoot, materializationSpec)));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> recovered.inspectMaterialization(materializationSpec));
    }

    @Test
    void absentPendingBothAndStructurallyInvalidTreesFailWithoutMutation()
            throws Exception {
        assertFailureWithoutMutation(
                "absent",
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                (providerRoot, materializationSpec) -> {
                },
                0);
        assertFailureWithoutMutation(
                "pending",
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                (providerRoot, materializationSpec) ->
                        Files.createDirectories(pending(providerRoot, materializationSpec)),
                0);
        assertFailureWithoutMutation(
                "both",
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                (providerRoot, materializationSpec) -> {
                    publish(providerRoot, materializationSpec);
                    Files.createDirectories(pending(providerRoot, materializationSpec));
                },
                0);
        assertFailureWithoutMutation(
                "extra",
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                (providerRoot, materializationSpec) -> {
                    publish(providerRoot, materializationSpec);
                    Files.writeString(
                            container(providerRoot, materializationSpec)
                                    .resolve("unexpected"),
                            "preserve");
                },
                0);
        assertFailureWithoutMutation(
                "missing",
                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                (providerRoot, materializationSpec) -> {
                    publish(providerRoot, materializationSpec);
                    Files.delete(
                            container(providerRoot, materializationSpec)
                                    .resolve("data/paper.txt"));
                },
                1);
        assertFailureWithoutMutation(
                "mutated",
                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                (providerRoot, materializationSpec) -> {
                    publish(providerRoot, materializationSpec);
                    Files.writeString(
                            container(providerRoot, materializationSpec)
                                    .resolve("data/paper.txt"),
                            "mutated");
                },
                1);
        assertFailureWithoutMutation(
                "staging",
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                (providerRoot, materializationSpec) -> {
                    publish(providerRoot, materializationSpec);
                    Files.writeString(
                            container(providerRoot, materializationSpec)
                                    .resolve("staging/leftover"),
                            "preserve");
                },
                0);
    }

    @Test
    void failedAdoptionLeavesNoRegistrationAndCorrectedRetrySucceeds()
            throws Exception {
        Path providerRoot = root.resolve("corrected-retry");
        WorkspaceMaterializationSpec materializationSpec =
                spec("corrected-retry");
        publish(providerRoot, materializationSpec);
        Path paper = container(providerRoot, materializationSpec)
                .resolve("data/paper.txt");
        Files.writeString(paper, "wrong");
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> {
                    loads.incrementAndGet();
                    return source();
                });

        assertCode(
                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                () -> provider.inspectMaterialization(materializationSpec));
        Files.writeString(paper, "paper");
        VerifiedWorkspaceMaterialization recovered =
                provider.inspectMaterialization(materializationSpec);

        assertNotNull(recovered);
        assertEquals(2, loads.get());
        assertBytes(
                "paper",
                provider.read(recovered.workspace(), new ProjectPath("paper.txt")));
    }

    @Test
    void publishedLinksFailClosedWithoutSourceLoadOrMutation()
            throws Exception {
        Path providerRoot = root.resolve("link-rejection");
        WorkspaceMaterializationSpec materializationSpec =
                spec("link-rejection");
        publish(providerRoot, materializationSpec);
        Path paper = container(providerRoot, materializationSpec)
                .resolve("data/paper.txt");
        Path target = root.resolve("outside.txt");
        Files.writeString(target, "outside");
        Files.delete(paper);
        try {
            Files.createSymbolicLink(paper, target);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("symbolic links are unavailable");
        }
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> {
                    loads.incrementAndGet();
                    return source();
                });
        List<String> before = treeState(providerRoot);

        assertCode(
                WorkspaceErrorCode.LINK_ESCAPE,
                () -> provider.inspectMaterialization(materializationSpec));
        assertEquals(before, treeState(providerRoot));
        assertEquals(0, loads.get());
        assertEquals("outside", Files.readString(target));
    }

    @Test
    void indeterminateOccupancyFailsBeforeSourceLoadAndLeavesTreeUntouched()
            throws Exception {
        Path providerRoot = root.resolve("indeterminate");
        WorkspaceMaterializationSpec materializationSpec =
                spec("indeterminate");
        publish(providerRoot, materializationSpec);
        Path published = container(providerRoot, materializationSpec);
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> {
                    loads.incrementAndGet();
                    return source();
                },
                (source, target, replace) -> {
                    throw new AssertionError("inspection must not move files");
                },
                Files::write,
                LocalWorkspaceProvider::readBoundedNoFollow,
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree,
                new DelegatingPathProbe() {
                    @Override
                    public boolean exists(Path path) {
                        return path.equals(published)
                                ? false
                                : super.exists(path);
                    }

                    @Override
                    public boolean notExists(Path path) {
                        return path.equals(published)
                                ? false
                                : super.notExists(path);
                    }
                },
                ignored -> {
                });
        List<String> before = treeState(providerRoot);

        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(materializationSpec));
        assertEquals(before, treeState(providerRoot));
        assertEquals(0, loads.get());
    }

    @Test
    void concurrentProvidersAdoptWithoutFilesystemMutationOrDivergentFacts()
            throws Exception {
        Path providerRoot = root.resolve("concurrent");
        WorkspaceMaterializationSpec materializationSpec =
                spec("concurrent-recovery");
        VerifiedWorkspaceMaterialization published =
                publish(providerRoot, materializationSpec);
        AtomicInteger loads = new AtomicInteger();
        ProjectVersionSource source = ignored -> {
            loads.incrementAndGet();
            return source();
        };
        LocalWorkspaceProvider first =
                new LocalWorkspaceProvider(providerRoot, source);
        LocalWorkspaceProvider second =
                new LocalWorkspaceProvider(providerRoot, source);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> before = treeState(providerRoot);
        try {
            Future<Attempt> left = executor.submit(
                    () -> attempt(first, materializationSpec, start));
            Future<Attempt> right = executor.submit(
                    () -> attempt(second, materializationSpec, start));
            start.countDown();
            List<Attempt> attempts = List.of(
                    left.get(5, TimeUnit.SECONDS),
                    right.get(5, TimeUnit.SECONDS));
            List<Attempt> successes = attempts.stream()
                    .filter(attempt -> attempt.result() != null)
                    .toList();

            assertFalse(successes.isEmpty());
            for (Attempt attempt : attempts) {
                if (attempt.failure() != null) {
                    assertEquals(
                            WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                            attempt.failure().code());
                    VerifiedWorkspaceMaterialization sequential =
                            attempt.provider().inspectMaterialization(
                                    materializationSpec);
                    assertEquals(published, sequential);
                    assertBytes(
                            "paper",
                            attempt.provider().read(
                                    sequential.workspace(),
                                    new ProjectPath("paper.txt")));
                } else {
                    assertEquals(published, attempt.result());
                    assertBytes(
                            "paper",
                            attempt.provider().read(
                                    attempt.result().workspace(),
                                    new ProjectPath("paper.txt")));
                }
            }
            assertEquals(2, loads.get());
            assertEquals(before, treeState(providerRoot));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertFailureWithoutMutation(
            String name,
            WorkspaceErrorCode expected,
            TreeSetup setup,
            int expectedLoads)
            throws Exception {
        Path providerRoot = root.resolve("failure-" + name);
        Files.createDirectories(providerRoot);
        WorkspaceMaterializationSpec materializationSpec =
                spec("failure-" + name);
        setup.apply(providerRoot, materializationSpec);
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> {
                    loads.incrementAndGet();
                    return source();
                });
        List<String> before = treeState(providerRoot);

        assertCode(
                expected,
                () -> provider.inspectMaterialization(materializationSpec));

        assertEquals(before, treeState(providerRoot));
        assertEquals(expectedLoads, loads.get());
    }

    private static VerifiedWorkspaceMaterialization publish(
            Path providerRoot,
            WorkspaceMaterializationSpec materializationSpec) {
        return new LocalWorkspaceProvider(providerRoot, ignored -> source())
                .materialize(materializationSpec);
    }

    private static ProjectVersionSnapshot source() {
        return snapshot(file("paper.txt", "paper"));
    }

    private static Path container(
            Path providerRoot,
            WorkspaceMaterializationSpec materializationSpec) {
        return providerRoot.resolve(
                "ws-" + WorkspaceHashes.sha256Text(
                        materializationSpec.workspaceId().value()));
    }

    private static Path pending(
            Path providerRoot,
            WorkspaceMaterializationSpec materializationSpec) {
        return providerRoot.resolve(
                "pending-" + WorkspaceHashes.sha256Text(
                        materializationSpec.workspaceId().value()));
    }

    private static List<String> treeState(Path root) throws IOException {
        List<String> state = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                String relative = root.relativize(path)
                        .toString()
                        .replace(path.getFileSystem().getSeparator(), "/");
                if (attributes.isSymbolicLink()) {
                    state.add("L:" + relative + ":" + Files.readSymbolicLink(path));
                } else if (attributes.isDirectory()) {
                    state.add("D:" + relative);
                } else if (attributes.isRegularFile()) {
                    state.add(
                            "F:" + relative + ":" + attributes.size() + ":"
                                    + WorkspaceHashes.sha256(
                                            path,
                                            Long.MAX_VALUE,
                                            "testSnapshot",
                                            null));
                } else {
                    state.add("O:" + relative);
                }
            }
        }
        return List.copyOf(state);
    }

    private static Attempt attempt(
            LocalWorkspaceProvider provider,
            WorkspaceMaterializationSpec materializationSpec,
            CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to start");
            }
            return new Attempt(
                    provider,
                    provider.inspectMaterialization(materializationSpec),
                    null);
        } catch (WorkspaceException exception) {
            return new Attempt(provider, null, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }

    private static void assertCode(
            WorkspaceErrorCode expected,
            Runnable operation) {
        WorkspaceException failure =
                assertThrows(WorkspaceException.class, operation::run);
        assertEquals(expected, failure.code());
    }

    @FunctionalInterface
    private interface TreeSetup {
        void apply(
                Path providerRoot,
                WorkspaceMaterializationSpec materializationSpec)
                throws Exception;
    }

    private static class DelegatingPathProbe
            implements LocalWorkspaceProvider.WorkspacePathProbe {
        @Override
        public boolean exists(Path path) {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean notExists(Path path) {
            return Files.notExists(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public BasicFileAttributes readAttributes(Path path)
                throws IOException {
            return Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        }
    }

    private record Attempt(
            LocalWorkspaceProvider provider,
            VerifiedWorkspaceMaterialization result,
            WorkspaceException failure) {
    }
}
