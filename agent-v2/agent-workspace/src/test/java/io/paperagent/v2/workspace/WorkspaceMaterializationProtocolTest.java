package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.GENEROUS_LIMITS;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMaterializationProtocolTest {
    @TempDir
    Path root;

    @Test
    void resultIsDerivedOpaqueAndExactReplayDoesNoWorkOrOverwrite() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicReference<ProjectVersionSnapshot> source =
                new AtomicReference<>(snapshot(file("paper.txt", "original")));
        LocalWorkspaceProvider provider = provider(
                requested -> {
                    loads.incrementAndGet();
                    return source.get();
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
        WorkspaceMaterializationSpec spec = spec("replay");

        VerifiedWorkspaceMaterialization first = provider.materialize(spec);
        provider.replace(
                first.workspace(),
                new ProjectPath("paper.txt"),
                "edited".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        source.set(null);
        VerifiedWorkspaceMaterialization replay = provider.materialize(spec);

        assertSame(first, replay);
        assertEquals(
                new WorkspaceRef(spec.workspaceId(), spec.sourceProjectVersion()),
                first.workspace());
        assertEquals(1, loads.get());
        assertEquals(1, writes.get());
        assertEquals(1, publishes.get());
        assertBytes("edited", provider.read(first.workspace(), new ProjectPath("paper.txt")));
        String text = first.toString();
        assertFalse(text.contains(spec.workspaceId().value()));
        assertFalse(text.contains(spec.sourceProjectVersion().projectId()));
        assertFalse(text.contains(first.sourceManifestFingerprint().value()));
    }

    @Test
    void activeConflictsOnEverySpecComponentBeforeSourceOrMutation() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
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
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec original = spec("conflict");
        provider.materialize(original);
        int originalWrites = writes.get();

        List<WorkspaceMaterializationSpec> conflicts = List.of(
                new WorkspaceMaterializationSpec(
                        original.workspaceId(),
                        new ProjectVersionRef("other-project", "version-1"),
                        original.limits()),
                new WorkspaceMaterializationSpec(
                        original.workspaceId(),
                        new ProjectVersionRef("project-1", "other-version"),
                        original.limits()),
                new WorkspaceMaterializationSpec(
                        original.workspaceId(),
                        original.sourceProjectVersion(),
                        new WorkspaceMaterializationLimits(
                                original.limits().maxFileBytes() + 1,
                                original.limits().maxAggregateBytes(),
                                original.limits().maxFiles())),
                new WorkspaceMaterializationSpec(
                        original.workspaceId(),
                        original.sourceProjectVersion(),
                        new WorkspaceMaterializationLimits(
                                original.limits().maxFileBytes(),
                                original.limits().maxAggregateBytes() + 1,
                                original.limits().maxFiles())),
                new WorkspaceMaterializationSpec(
                        original.workspaceId(),
                        original.sourceProjectVersion(),
                        new WorkspaceMaterializationLimits(
                                original.limits().maxFileBytes(),
                                original.limits().maxAggregateBytes(),
                                original.limits().maxFiles() + 1)));

        for (WorkspaceMaterializationSpec conflict : conflicts) {
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
                    () -> provider.materialize(conflict));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
                    () -> provider.inspectMaterialization(conflict));
        }
        assertEquals(1, loads.get());
        assertEquals(originalWrites, writes.get());
        assertEquals(1, publishes.get());
        assertEquals(0, deletes.get());
    }

    @Test
    void exactReplayAndInspectReturnOriginalFactAfterNormalDataChanges() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> {
                    loads.incrementAndGet();
                    return snapshot(
                            file("edited.txt", "before"),
                            file("deleted.txt", "delete-me"));
                },
                (target, content, options) -> {
                    writes.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec spec = spec("inspect");
        VerifiedWorkspaceMaterialization original = provider.materialize(spec);
        provider.replace(
                original.workspace(),
                new ProjectPath("edited.txt"),
                "changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        provider.create(
                original.workspace(),
                new ProjectPath("created.txt"),
                "created".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        provider.delete(
                original.workspace(),
                new ProjectPath("deleted.txt"));
        int writesBeforeReplay = writes.get();

        assertSame(original, provider.materialize(spec));
        assertSame(original, provider.inspectMaterialization(spec));
        assertEquals(1, loads.get());
        assertEquals(writesBeforeReplay, writes.get());
        assertEquals(1, publishes.get());
        assertEquals(0, deletes.get());
        assertBytes(
                "changed",
                provider.read(original.workspace(), new ProjectPath("edited.txt")));
        assertBytes(
                "created",
                provider.read(original.workspace(), new ProjectPath("created.txt")));
        assertFalse(Files.exists(
                container(root, spec.workspaceId())
                        .resolve("data")
                        .resolve("deleted.txt")));
    }

    @Test
    void activeContainerExtraRootEntryIsPartialReadOnlyAndOpaque() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
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
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec spec = spec("active-extra-root");
        provider.materialize(spec);
        Path workspaceContainer = container(root, spec.workspaceId());
        int writesAfterSuccess = writes.get();

        Path extraFile = workspaceContainer.resolve("extra-root.txt");
        Files.writeString(extraFile, "preserve");
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(spec),
                extraFile);
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(spec),
                extraFile);
        assertEquals("preserve", Files.readString(extraFile));
        assertEquals(1, loads.get());
        assertEquals(writesAfterSuccess, writes.get());
        assertEquals(1, publishes.get());
        assertEquals(0, deletes.get());

        Files.delete(extraFile);
        Path extraDirectory = workspaceContainer.resolve("extra-root-directory");
        Files.createDirectory(extraDirectory);
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(spec),
                extraDirectory);
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(spec),
                extraDirectory);
        assertTrue(Files.isDirectory(extraDirectory));
        assertEquals(1, loads.get());
        assertEquals(writesAfterSuccess, writes.get());
        assertEquals(1, publishes.get());
        assertEquals(0, deletes.get());
    }

    @Test
    void activeStagingLeftoverIsPartialReadOnlyAndPreserved() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
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
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec spec = spec("active-staging-leftover");
        provider.materialize(spec);
        Path leftover = container(root, spec.workspaceId())
                .resolve("staging")
                .resolve("leftover.tmp");
        Files.writeString(leftover, "preserve");
        int writesAfterSuccess = writes.get();

        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(spec),
                leftover);
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(spec),
                leftover);
        assertEquals("preserve", Files.readString(leftover));
        assertEquals(1, loads.get());
        assertEquals(writesAfterSuccess, writes.get());
        assertEquals(1, publishes.get());
        assertEquals(0, deletes.get());
    }

    @Test
    void unknownFinalAndPendingOccupancyAreNeverLoadedAdoptedOrDeleted() throws Exception {
        for (String prefix : List.of("ws-", "pending-")) {
            for (String kind : List.of("file", "directory")) {
                Path providerRoot = root.resolve(prefix.replace("-", "") + "-" + kind);
                Files.createDirectories(providerRoot);
                AtomicInteger loads = new AtomicInteger();
                AtomicInteger deletes = new AtomicInteger();
                LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                        providerRoot,
                        ignored -> {
                            loads.incrementAndGet();
                            return snapshot(file("paper.txt", "paper"));
                        },
                        Files::write,
                        LocalWorkspaceProvider::defaultPublish,
                        target -> {
                            deletes.incrementAndGet();
                            LocalWorkspaceProvider.deleteTree(target);
                        });
                WorkspaceMaterializationSpec spec = spec("occupied-" + prefix + kind);
                Path occupied = providerRoot.resolve(
                        prefix + WorkspaceHashes.sha256Text(spec.workspaceId().value()));
                Path sentinel;
                if (kind.equals("file")) {
                    Files.writeString(occupied, "preserve");
                    sentinel = occupied;
                } else {
                    Files.createDirectories(occupied);
                    sentinel = occupied.resolve("sentinel.txt");
                    Files.writeString(sentinel, "preserve");
                }

                assertCode(
                        WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                        () -> provider.materialize(spec));
                assertCode(
                        WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                        () -> provider.inspectMaterialization(spec));
                assertCode(
                        WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                        () -> provider.cleanup(new WorkspaceRef(
                                spec.workspaceId(),
                                spec.sourceProjectVersion())));
                assertEquals(0, loads.get());
                assertEquals(0, deletes.get());
                assertEquals("preserve", Files.readString(sentinel));
            }
        }
    }

    @Test
    void registeredMissingWorkspaceIsPartialAndNeverReconstructed() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(root, ignored -> {
            loads.incrementAndGet();
            return snapshot(file("paper.txt", "paper"));
        });
        WorkspaceMaterializationSpec spec = spec("missing-active");
        VerifiedWorkspaceMaterialization result = provider.materialize(spec);
        LocalWorkspaceProvider.deleteTree(container(root, spec.workspaceId()));

        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.list(result.workspace()));
        assertEquals(1, loads.get());
        assertFalse(Files.exists(container(root, spec.workspaceId())));
    }

    @Test
    void sourceFingerprintDriftFailsBeforeWorkspacePathAndPreservesExistingWorkspace() {
        AtomicReference<ProjectVersionSnapshot> source =
                new AtomicReference<>(snapshot(file("paper.txt", "first")));
        AtomicInteger deletes = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> source.get(),
                Files::write,
                LocalWorkspaceProvider::defaultPublish,
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec firstSpec = spec("first-pin");
        VerifiedWorkspaceMaterialization first = provider.materialize(firstSpec);
        source.set(snapshot(file("paper.txt", "second")));
        WorkspaceMaterializationSpec secondSpec = spec("second-pin");

        assertCode(
                WorkspaceErrorCode.SOURCE_MANIFEST_FINGERPRINT_MISMATCH,
                () -> provider.materialize(secondSpec));
        assertFalse(Files.exists(container(root, secondSpec.workspaceId())));
        assertFalse(Files.exists(pending(root, secondSpec.workspaceId())));
        assertEquals(0, deletes.get());
        assertBytes("first", provider.read(first.workspace(), new ProjectPath("paper.txt")));
    }

    @Test
    void failedCopyPinsSourceAndChangedExactRetryCannotSucceed() {
        AtomicReference<ProjectVersionSnapshot> source =
                new AtomicReference<>(snapshot(file("paper.txt", "first")));
        AtomicInteger writes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> source.get(),
                (target, content, options) -> {
                    writes.incrementAndGet();
                    throw new IOException("forced");
                },
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = spec("pin-before-copy");

        assertCode(WorkspaceErrorCode.IO_FAILURE, () -> provider.materialize(spec));
        source.set(snapshot(file("paper.txt", "changed")));
        assertCode(
                WorkspaceErrorCode.SOURCE_MANIFEST_FINGERPRINT_MISMATCH,
                () -> provider.materialize(spec));
        assertEquals(1, writes.get());
        assertFalse(Files.exists(container(root, spec.workspaceId())));
        assertFalse(Files.exists(pending(root, spec.workspaceId())));
    }

    @Test
    void postWriteVerificationRejectsTruncationMutationOmissionAndExtraFile() {
        List<WorkspaceMaterializationWriter> corruptWriters = List.of(
                (target, content, options) ->
                        Files.write(target, new byte[]{content[0]}, options),
                (target, content, options) -> {
                    byte[] changed = content.clone();
                    changed[0] ^= 1;
                    Files.write(target, changed, options);
                },
                (target, content, options) -> {
                    // Deliberately omit the declared file.
                },
                (target, content, options) -> {
                    Files.write(target, content, options);
                    Files.writeString(
                            target.getParent().resolve("extra.txt"),
                            "extra",
                            StandardOpenOption.CREATE_NEW);
                },
                (target, content, options) -> {
                    Files.write(target, content, options);
                    Files.writeString(
                            target.getParent().getParent().resolve("extra-root.txt"),
                            "extra",
                            StandardOpenOption.CREATE_NEW);
                });

        for (int index = 0; index < corruptWriters.size(); index++) {
            Path providerRoot = root.resolve("corrupt-" + index);
            LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                    providerRoot,
                    ignored -> snapshot(file("paper.txt", "paper")),
                    corruptWriters.get(index),
                    LocalWorkspaceProvider::defaultPublish,
                    LocalWorkspaceProvider::deleteTree);
            WorkspaceMaterializationSpec spec = spec("corrupt-" + index);

            assertCode(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    () -> provider.materialize(spec));
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                    () -> provider.inspectMaterialization(spec));
            assertFalse(Files.exists(container(providerRoot, spec.workspaceId())));
            assertFalse(Files.exists(pending(providerRoot, spec.workspaceId())));
        }
    }

    @Test
    void pendingStagingDeletionOrRegularFileReplacementIsVerificationFailure()
            throws Exception {
        for (String corruption : List.of("missing", "regular-file")) {
            Path providerRoot = root.resolve("pending-staging-" + corruption);
            AtomicInteger publishes = new AtomicInteger();
            LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                    providerRoot,
                    ignored -> snapshot(file("paper.txt", "paper")),
                    (target, content, options) -> {
                        Files.write(target, content, options);
                        Path staging = target.getParent()
                                .getParent()
                                .resolve("staging");
                        Files.delete(staging);
                        if (corruption.equals("regular-file")) {
                            Files.writeString(
                                    staging,
                                    "corrupt",
                                    StandardOpenOption.CREATE_NEW);
                        }
                    },
                    (pending, target) -> {
                        publishes.incrementAndGet();
                        LocalWorkspaceProvider.defaultPublish(pending, target);
                    },
                    LocalWorkspaceProvider::deleteTree);
            WorkspaceMaterializationSpec spec =
                    spec("pending-staging-" + corruption);

            WorkspaceException failure = assertThrows(
                    WorkspaceException.class,
                    () -> provider.materialize(spec));

            assertEquals(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    failure.code());
            assertTrue(failure.projectPath().isEmpty());
            assertEquals(null, failure.getCause());
            assertFalse(failure.getMessage().contains(providerRoot.toString()));
            assertEquals(0, publishes.get());
            assertCode(
                    WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                    () -> provider.inspectMaterialization(spec));
            assertFalse(Files.exists(container(providerRoot, spec.workspaceId())));
            assertFalse(Files.exists(pending(providerRoot, spec.workspaceId())));
        }
    }

    @Test
    void atomicMoveUnsupportedHasNoFallbackAndDoesNotRegisterActive() {
        AtomicInteger publishes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> snapshot(file("paper.txt", "paper")),
                Files::write,
                (pending, target) -> {
                    publishes.incrementAndGet();
                    throw new AtomicMoveNotSupportedException(
                            pending.toString(),
                            target.toString(),
                            "forced");
                },
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = spec("no-atomic-fallback");

        assertCode(
                WorkspaceErrorCode.ATOMIC_PUBLISH_NOT_SUPPORTED,
                () -> provider.materialize(spec));
        assertEquals(1, publishes.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                () -> provider.inspectMaterialization(spec));
        assertFalse(Files.exists(container(root, spec.workspaceId())));
        assertFalse(Files.exists(pending(root, spec.workspaceId())));
    }

    @Test
    void failedMaterializationCleanupIsRecoverableWithoutReplacingOriginalFailure()
            throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger deleteAttempts = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> {
                    loads.incrementAndGet();
                    return snapshot(
                            file("a-first.txt", "first"),
                            file("b-second.txt", "second"));
                },
                (target, content, options) -> {
                    if (writes.incrementAndGet() == 2) {
                        throw new IOException("forced writer failure at " + target);
                    }
                    Files.write(target, content, options);
                },
                LocalWorkspaceProvider::defaultPublish,
                target -> {
                    if (deleteAttempts.incrementAndGet() == 1) {
                        Files.deleteIfExists(target.resolve("data/a-first.txt"));
                        throw new IOException("forced partial delete at " + target);
                    }
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec spec = spec("failed-materialization-cleanup");
        WorkspaceRef workspace = new WorkspaceRef(
                spec.workspaceId(),
                spec.sourceProjectVersion());

        WorkspaceException original = assertThrows(
                WorkspaceException.class,
                () -> provider.materialize(spec));

        assertEquals(WorkspaceErrorCode.IO_FAILURE, original.code());
        assertTrue(original.projectPath().isEmpty());
        assertEquals(null, original.getCause());
        assertFalse(original.getMessage().contains(root.toString()));
        assertFalse(original.getMessage().contains("forced writer failure"));
        assertFalse(original.getMessage().contains("forced partial delete"));
        assertEquals(1, loads.get());
        assertEquals(2, writes.get());
        assertEquals(1, deleteAttempts.get());
        assertTrue(Files.isDirectory(pending(root, spec.workspaceId())));

        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.list(workspace));
        assertEquals(1, loads.get());
        assertEquals(1, deleteAttempts.get());

        WorkspaceRef wrong = new WorkspaceRef(
                spec.workspaceId(),
                new ProjectVersionRef("project-1", "wrong-version"));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH,
                () -> provider.cleanup(wrong));
        assertEquals(1, deleteAttempts.get());

        provider.cleanup(workspace);
        provider.cleanup(workspace);

        assertEquals(2, deleteAttempts.get());
        assertFalse(Files.exists(pending(root, spec.workspaceId())));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> provider.materialize(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> provider.inspectMaterialization(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> provider.list(workspace));
    }

    @Test
    void cleanupRetiresIdentityAndPreventsStaleReferenceAba() {
        LocalWorkspaceProvider provider =
                new LocalWorkspaceProvider(root, ignored -> snapshot(file("paper.txt", "paper")));
        WorkspaceMaterializationSpec spec = spec("retired");
        VerifiedWorkspaceMaterialization result = provider.materialize(spec);

        provider.cleanup(result.workspace());
        provider.cleanup(result.workspace());

        assertCode(WorkspaceErrorCode.WORKSPACE_RETIRED, () -> provider.materialize(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> provider.materialize(new WorkspaceMaterializationSpec(
                        spec.workspaceId(),
                        spec.sourceProjectVersion(),
                        new WorkspaceMaterializationLimits(1, 1, 1))));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> provider.inspectMaterialization(spec));
        assertCode(WorkspaceErrorCode.WORKSPACE_RETIRED, () -> provider.list(result.workspace()));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH,
                () -> provider.cleanup(new WorkspaceRef(
                        spec.workspaceId(),
                        new ProjectVersionRef("project-1", "different"))));
        assertFalse(Files.exists(container(root, spec.workspaceId())));
    }

    @Test
    void incompleteCleanupRemainsPendingBlocksUseAndExactRetryCompletes() throws Exception {
        AtomicInteger deleteAttempts = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> snapshot(
                        file("a.txt", "a"),
                        file("nested/b.txt", "b")),
                Files::write,
                LocalWorkspaceProvider::defaultPublish,
                target -> {
                    if (deleteAttempts.incrementAndGet() == 1) {
                        Files.delete(target.resolve("data").resolve("a.txt"));
                        throw new IOException("forced partial cleanup");
                    }
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec spec = spec("cleanup-pending");
        VerifiedWorkspaceMaterialization result = provider.materialize(spec);

        assertCode(WorkspaceErrorCode.IO_FAILURE, () -> provider.cleanup(result.workspace()));
        assertCode(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, () -> provider.list(result.workspace()));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(spec));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(spec));
        provider.cleanup(result.workspace());
        provider.cleanup(result.workspace());

        assertEquals(2, deleteAttempts.get());
        assertCode(WorkspaceErrorCode.WORKSPACE_RETIRED, () -> provider.materialize(spec));
        assertFalse(Files.exists(container(root, spec.workspaceId())));
    }

    @Test
    void samePinnedSourceCanMaterializeDifferentIdsAndLimits() {
        LocalWorkspaceProvider provider =
                new LocalWorkspaceProvider(root, ignored -> snapshot(file("paper.txt", "paper")));
        WorkspaceMaterializationSpec first = spec("same-source-first");
        WorkspaceMaterializationSpec second = new WorkspaceMaterializationSpec(
                new WorkspaceId("same-source-second"),
                VERSION,
                new WorkspaceMaterializationLimits(2048, 16384, 64));

        VerifiedWorkspaceMaterialization firstResult = provider.materialize(first);
        VerifiedWorkspaceMaterialization secondResult = provider.materialize(second);

        assertNotEquals(firstResult.workspace(), secondResult.workspace());
        assertEquals(
                firstResult.sourceManifestFingerprint(),
                secondResult.sourceManifestFingerprint());
    }

    @Test
    void verifiedResultRejectsMissingComponentsWithoutLeakingValues() {
        ContentHash fingerprint = WorkspaceManifestFingerprint.calculate(snapshot());

        assertCode(
                WorkspaceErrorCode.REQUIRED_VALUE_MISSING,
                () -> new VerifiedWorkspaceMaterialization(null, fingerprint));
        assertCode(
                WorkspaceErrorCode.REQUIRED_VALUE_MISSING,
                () -> new VerifiedWorkspaceMaterialization(spec("missing-result"), null));
    }

    @Test
    void nullSpecsFailBeforeSourceOrWorkspaceAccess() {
        AtomicInteger loads = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(root, ignored -> {
            loads.incrementAndGet();
            return snapshot();
        });

        assertCode(
                WorkspaceErrorCode.REQUIRED_VALUE_MISSING,
                () -> provider.materialize(null));
        assertCode(
                WorkspaceErrorCode.REQUIRED_VALUE_MISSING,
                () -> provider.inspectMaterialization(null));
        assertEquals(0, loads.get());
    }

    @Test
    void sourceSnapshotAndExternalSentinelRemainUnchangedAcrossLifecycleFailures()
            throws Exception {
        Path sentinel = root.resolve("external-sentinel.txt");
        Files.writeString(sentinel, "preserve");
        ProjectFileSnapshot sourceFile = file("paper.txt", "source");
        ProjectVersionSnapshot sourceSnapshot = snapshot(sourceFile);
        Path successfulRoot = root.resolve("successful-provider");
        LocalWorkspaceProvider successful =
                new LocalWorkspaceProvider(successfulRoot, ignored -> sourceSnapshot);
        WorkspaceMaterializationSpec spec = spec("sentinel-success");
        VerifiedWorkspaceMaterialization result = successful.materialize(spec);
        assertCode(
                WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
                () -> successful.materialize(new WorkspaceMaterializationSpec(
                        spec.workspaceId(),
                        spec.sourceProjectVersion(),
                        new WorkspaceMaterializationLimits(1, 1, 1))));
        successful.cleanup(result.workspace());

        Path failedRoot = root.resolve("failed-provider");
        LocalWorkspaceProvider failed = new LocalWorkspaceProvider(
                failedRoot,
                ignored -> sourceSnapshot,
                (target, content, options) -> Files.write(target, new byte[]{0}, options),
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree);
        assertCode(
                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                () -> failed.materialize(spec("sentinel-failure")));

        assertEquals("preserve", Files.readString(sentinel));
        assertBytes("source", sourceFile.content());
        assertBytes("source", sourceSnapshot.files().get(0).content());
    }

    @Test
    void postWriteFileCountLimitFailsBeforePublishAndCleansPending() {
        AtomicInteger publishes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> snapshot(file("paper.txt", "p")),
                (target, content, options) -> {
                    Files.write(target, content, options);
                    Files.writeString(
                            target.getParent().resolve("extra.txt"),
                            "x",
                            StandardOpenOption.CREATE_NEW);
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = new WorkspaceMaterializationSpec(
                new WorkspaceId("verify-count-limit"),
                VERSION,
                new WorkspaceMaterializationLimits(8, 8, 1));

        assertCode(
                WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED,
                () -> provider.materialize(spec));
        assertEquals(0, publishes.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                () -> provider.inspectMaterialization(spec));
        assertFalse(Files.exists(container(root, spec.workspaceId())));
        assertFalse(Files.exists(pending(root, spec.workspaceId())));
    }

    @Test
    void postWriteAggregateLimitFailsBeforeFullTreeHashingOrPublishAndCleansPending() {
        AtomicInteger publishes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> snapshot(file("paper.txt", "p")),
                (target, content, options) -> {
                    Files.write(target, content, options);
                    Files.writeString(
                            target.getParent().resolve("extra.txt"),
                            "x",
                            StandardOpenOption.CREATE_NEW);
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = new WorkspaceMaterializationSpec(
                new WorkspaceId("verify-aggregate-limit"),
                VERSION,
                new WorkspaceMaterializationLimits(8, 1, 2));

        assertCode(
                WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
                () -> provider.materialize(spec));
        assertEquals(0, publishes.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                () -> provider.inspectMaterialization(spec));
        assertFalse(Files.exists(container(root, spec.workspaceId())));
        assertFalse(Files.exists(pending(root, spec.workspaceId())));
    }

    @Test
    void unexpectedEmptyDirectoryFailsImmediatelyWithoutPublishOrActiveState() {
        AtomicInteger publishes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> snapshot(file("paper.txt", "paper")),
                (target, content, options) -> {
                    Files.write(target, content, options);
                    Files.createDirectories(
                            target.getParent().resolve("unexpected/deep"));
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = spec("unexpected-empty-directory");

        assertCode(
                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                () -> provider.materialize(spec));
        assertEquals(0, publishes.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                () -> provider.inspectMaterialization(spec));
        assertFalse(Files.exists(container(root, spec.workspaceId())));
        assertFalse(Files.exists(pending(root, spec.workspaceId())));
    }

    @Test
    void hostValidButV2InvalidExtraFilenameMapsToOpaqueVerificationFailure() {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                        .contains("win"),
                "Windows treats backslash as a separator; Ubuntu CI must execute this test");
        String hostileFilename = "host\\only.txt";
        AtomicInteger publishes = new AtomicInteger();
        LocalWorkspaceProvider provider = provider(
                ignored -> snapshot(file("paper.txt", "paper")),
                (target, content, options) -> {
                    Files.write(target, content, options);
                    Files.writeString(
                            target.getParent().resolve(hostileFilename),
                            "extra",
                            StandardOpenOption.CREATE_NEW);
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);
        WorkspaceMaterializationSpec spec = spec("invalid-host-filename");

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> provider.materialize(spec));

        assertEquals(
                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                failure.code());
        assertTrue(failure.projectPath().isEmpty());
        assertFalse(failure.getMessage().contains(hostileFilename));
        assertFalse(failure.getMessage().contains(root.toString()));
        assertEquals(0, publishes.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                () -> provider.inspectMaterialization(spec));
        assertFalse(Files.exists(container(root, spec.workspaceId())));
        assertFalse(Files.exists(pending(root, spec.workspaceId())));
    }

    @Test
    void falseExistsAndFalseNotExistsIsPartialAcrossUnknownStateOperations()
            throws Exception {
        WorkspaceMaterializationSpec materializationSpec =
                spec("indeterminate-unknown");
        Path unknownFinal =
                container(root, materializationSpec.workspaceId());
        Files.createDirectory(unknownFinal);
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
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
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                },
                new DelegatingPathProbe() {
                    @Override
                    public boolean exists(Path path) {
                        return path.equals(unknownFinal)
                                ? false
                                : super.exists(path);
                    }

                    @Override
                    public boolean notExists(Path path) {
                        return path.equals(unknownFinal)
                                ? false
                                : super.notExists(path);
                    }
                });
        WorkspaceRef workspace = new WorkspaceRef(
                materializationSpec.workspaceId(),
                materializationSpec.sourceProjectVersion());

        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(materializationSpec),
                unknownFinal);
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.inspectMaterialization(materializationSpec),
                unknownFinal);
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.cleanup(workspace),
                unknownFinal);
        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.list(workspace),
                unknownFinal);

        assertEquals(0, loads.get());
        assertEquals(0, writes.get());
        assertEquals(0, publishes.get());
        assertEquals(0, deletes.get());
        assertTrue(Files.isDirectory(unknownFinal));
    }

    @Test
    void contradictoryAndThrowingAbsenceSignalsAreAlwaysPartialBeforeWork()
            throws Exception {
        for (String mode : List.of(
                "contradictory",
                "exists-runtime",
                "not-exists-runtime")) {
            Path providerRoot = root.resolve(mode);
            Files.createDirectory(providerRoot);
            WorkspaceMaterializationSpec materializationSpec =
                    spec(mode);
            Path finalContainer =
                    container(providerRoot, materializationSpec.workspaceId());
            AtomicInteger loads = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger publishes = new AtomicInteger();
            AtomicInteger deletes = new AtomicInteger();
            LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                    providerRoot,
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
                    target -> {
                        deletes.incrementAndGet();
                        LocalWorkspaceProvider.deleteTree(target);
                    },
                    new DelegatingPathProbe() {
                        @Override
                        public boolean exists(Path path) {
                            if (path.equals(finalContainer)
                                    && mode.equals("exists-runtime")) {
                                throw new IllegalStateException(
                                        "sensitive exists failure");
                            }
                            if (path.equals(finalContainer)
                                    && mode.equals("contradictory")) {
                                return true;
                            }
                            return super.exists(path);
                        }

                        @Override
                        public boolean notExists(Path path) {
                            if (path.equals(finalContainer)
                                    && mode.equals("not-exists-runtime")) {
                                throw new IllegalStateException(
                                        "sensitive notExists failure");
                            }
                            if (path.equals(finalContainer)
                                    && mode.equals("contradictory")) {
                                return true;
                            }
                            return super.notExists(path);
                        }
                    });

            assertOpaqueCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> provider.materialize(materializationSpec),
                    finalContainer);
            assertEquals(0, loads.get(), mode);
            assertEquals(0, writes.get(), mode);
            assertEquals(0, publishes.get(), mode);
            assertEquals(0, deletes.get(), mode);
        }
    }

    @Test
    void definiteLinkWinsOverIndeterminateOtherWorkspacePathInBothPositions()
            throws Exception {
        for (boolean linkIsContainer : List.of(false, true)) {
            Path providerRoot =
                    root.resolve(linkIsContainer
                            ? "container-link"
                            : "pending-link");
            Files.createDirectory(providerRoot);
            WorkspaceMaterializationSpec materializationSpec =
                    spec(linkIsContainer
                            ? "container-link-priority"
                            : "pending-link-priority");
            Path finalContainer =
                    container(providerRoot, materializationSpec.workspaceId());
            Path pendingContainer =
                    pending(providerRoot, materializationSpec.workspaceId());
            Path linkPath =
                    linkIsContainer ? finalContainer : pendingContainer;
            Path indeterminatePath =
                    linkIsContainer ? pendingContainer : finalContainer;
            AtomicInteger linkAttributeReads = new AtomicInteger();
            AtomicInteger indeterminateExistsProbes =
                    new AtomicInteger();
            AtomicInteger loads = new AtomicInteger();
            LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                    providerRoot,
                    ignored -> {
                        loads.incrementAndGet();
                        return snapshot(file("paper.txt", "paper"));
                    },
                    Files::write,
                    LocalWorkspaceProvider::defaultPublish,
                    LocalWorkspaceProvider::deleteTree,
                    new DelegatingPathProbe() {
                        @Override
                        public boolean exists(Path path) {
                            if (path.equals(linkPath)) {
                                return true;
                            }
                            if (path.equals(indeterminatePath)) {
                                indeterminateExistsProbes.incrementAndGet();
                                return false;
                            }
                            return super.exists(path);
                        }

                        @Override
                        public boolean notExists(Path path) {
                            if (path.equals(linkPath)
                                    || path.equals(indeterminatePath)) {
                                return false;
                            }
                            return super.notExists(path);
                        }

                        @Override
                        public BasicFileAttributes readAttributes(Path path)
                                throws IOException {
                            if (path.equals(linkPath)) {
                                linkAttributeReads.incrementAndGet();
                                return linkLikeAttributes();
                            }
                            return super.readAttributes(path);
                        }
                    });

            assertOpaqueCode(
                    WorkspaceErrorCode.LINK_ESCAPE,
                    () -> provider.materialize(materializationSpec),
                    linkPath);
            assertEquals(1, linkAttributeReads.get());
            assertEquals(1, indeterminateExistsProbes.get());
            assertEquals(0, loads.get());
        }
    }

    @Test
    void absentRegistrationCleanupIsIdempotentAndPerformsNoWork() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
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
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec materializationSpec =
                spec("absent-cleanup");
        WorkspaceRef workspace = new WorkspaceRef(
                materializationSpec.workspaceId(),
                materializationSpec.sourceProjectVersion());

        provider.cleanup(workspace);
        provider.cleanup(workspace);

        assertEquals(0, loads.get());
        assertEquals(0, writes.get());
        assertEquals(0, publishes.get());
        assertEquals(0, deletes.get());
    }

    @Test
    void unreadableUnknownOccupancyIsOpaquePartialForIoAndRuntimeFailures()
            throws Exception {
        for (boolean runtime : List.of(false, true)) {
            Path providerRoot = root.resolve(runtime ? "runtime" : "io");
            Files.createDirectory(providerRoot);
            WorkspaceMaterializationSpec materializationSpec =
                    spec(runtime ? "attrs-runtime" : "attrs-io");
            Path unknownFinal =
                    container(providerRoot, materializationSpec.workspaceId());
            Files.createDirectory(unknownFinal);
            AtomicInteger loads = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger publishes = new AtomicInteger();
            AtomicInteger deletes = new AtomicInteger();
            LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                    providerRoot,
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
                    target -> {
                        deletes.incrementAndGet();
                        LocalWorkspaceProvider.deleteTree(target);
                    },
                    new DelegatingPathProbe() {
                        @Override
                        public BasicFileAttributes readAttributes(Path path)
                                throws IOException {
                            if (path.equals(unknownFinal)) {
                                if (runtime) {
                                    throw new IllegalStateException(
                                            "sensitive host failure");
                                }
                                throw new IOException(
                                        "sensitive host failure");
                            }
                            return super.readAttributes(path);
                        }
                    });

            assertOpaqueCode(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    () -> provider.materialize(materializationSpec),
                    unknownFinal);
            assertEquals(0, loads.get());
            assertEquals(0, writes.get());
            assertEquals(0, publishes.get());
            assertEquals(0, deletes.get());
            assertTrue(Files.isDirectory(unknownFinal));
        }
    }

    @Test
    void indeterminateFinalProbeAfterCopyNeverPublishesAndDeletesOnlyOwnedPending()
            throws Exception {
        WorkspaceMaterializationSpec materializationSpec =
                spec("indeterminate-before-publish");
        Path finalContainer =
                container(root, materializationSpec.workspaceId());
        Path ownedPending =
                pending(root, materializationSpec.workspaceId());
        AtomicInteger finalAbsenceProbes = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
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
                target -> {
                    deletes.incrementAndGet();
                    assertEquals(ownedPending, target);
                    LocalWorkspaceProvider.deleteTree(target);
                },
                new DelegatingPathProbe() {
                    @Override
                    public boolean notExists(Path path) {
                        if (path.equals(finalContainer)
                                && finalAbsenceProbes.incrementAndGet() == 3) {
                            return false;
                        }
                        return super.notExists(path);
                    }
                });

        assertOpaqueCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(materializationSpec),
                finalContainer);

        assertEquals(3, finalAbsenceProbes.get());
        assertEquals(1, loads.get());
        assertEquals(1, writes.get());
        assertEquals(0, publishes.get());
        assertEquals(1, deletes.get());
        assertFalse(Files.exists(finalContainer));
        assertFalse(Files.exists(ownedPending));
    }

    private LocalWorkspaceProvider provider(
            ProjectVersionSource source,
            WorkspaceMaterializationWriter writer,
            WorkspaceDirectoryPublisher publisher,
            WorkspaceTreeDeleter deleter) {
        return new LocalWorkspaceProvider(root, source, writer, publisher, deleter);
    }

    private static Path container(Path root, WorkspaceId id) {
        return root.resolve("ws-" + WorkspaceHashes.sha256Text(id.value()));
    }

    private static Path pending(Path root, WorkspaceId id) {
        return root.resolve("pending-" + WorkspaceHashes.sha256Text(id.value()));
    }

    private static void assertCode(WorkspaceErrorCode expected, Runnable action) {
        WorkspaceException failure = assertThrows(WorkspaceException.class, action::run);
        assertEquals(expected, failure.code());
    }

    private static void assertOpaqueCode(
            WorkspaceErrorCode expected,
            Runnable action,
            Path sensitivePath) {
        WorkspaceException failure = assertThrows(WorkspaceException.class, action::run);
        assertEquals(expected, failure.code());
        assertTrue(failure.projectPath().isEmpty());
        assertFalse(failure.getMessage().contains(sensitivePath.toString()));
        assertFalse(failure.getMessage().contains(sensitivePath.getFileName().toString()));
    }

    private static class DelegatingPathProbe
            implements LocalWorkspaceProvider.WorkspacePathProbe {
        @Override
        public boolean exists(Path path) {
            return Files.exists(
                    path,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean notExists(Path path) {
            return Files.notExists(
                    path,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public BasicFileAttributes readAttributes(Path path)
                throws IOException {
            return Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static BasicFileAttributes linkLikeAttributes() {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return FileTime.fromMillis(0);
            }

            @Override
            public FileTime lastAccessTime() {
                return FileTime.fromMillis(0);
            }

            @Override
            public FileTime creationTime() {
                return FileTime.fromMillis(0);
            }

            @Override
            public boolean isRegularFile() {
                return false;
            }

            @Override
            public boolean isDirectory() {
                return false;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return true;
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public Object fileKey() {
                return null;
            }
        };
    }
}
