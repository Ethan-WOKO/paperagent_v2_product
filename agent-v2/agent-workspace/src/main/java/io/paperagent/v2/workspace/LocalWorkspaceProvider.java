package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * JDK local-filesystem reference implementation.
 *
 * <p>Host paths are configuration details and are never returned by this API.
 * All public operations are synchronized so one provider instance cannot race
 * its own validation and mutation steps.</p>
 */
public final class LocalWorkspaceProvider implements WorkspacePort {
    private static final String DATA_DIRECTORY = "data";
    private static final String STAGING_DIRECTORY = "staging";
    private static final ConcurrentHashMap<MaterializationClaimKey, Object>
            MATERIALIZATION_CLAIMS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MaterializationClaimKey, RetiredTombstone>
            RETIRED_TOMBSTONES =
            new ConcurrentHashMap<>();
    private static final WorkspacePathProbe DEFAULT_PATH_PROBE =
            new WorkspacePathProbe() {
                @Override
                public boolean exists(Path path) {
                    return Files.exists(path, NOFOLLOW_LINKS);
                }

                @Override
                public boolean notExists(Path path) {
                    return Files.notExists(path, NOFOLLOW_LINKS);
                }

                @Override
                public BasicFileAttributes readAttributes(Path path)
                        throws IOException {
                    return Files.readAttributes(
                            path,
                            BasicFileAttributes.class,
                            NOFOLLOW_LINKS);
                }
            };
    private static final WorkspaceCaseProbeObserver NOOP_CASE_PROBE_OBSERVER =
            ignored -> {
            };

    private final Path providerRoot;
    private final ProjectVersionSource source;
    private final WorkspaceFileMover mover;
    private final WorkspaceMaterializationWriter materializationWriter;
    private final WorkspaceBackupReader backupReader;
    private final WorkspaceDirectoryPublisher directoryPublisher;
    private final WorkspaceTreeDeleter treeDeleter;
    private final WorkspacePathProbe pathProbe;
    private final boolean caseSensitive;
    private final Map<WorkspaceId, WorkspaceRegistration> workspaces = new HashMap<>();
    private final Map<ProjectVersionRef, ContentHash> sourceManifestFingerprints =
            new HashMap<>();

    public LocalWorkspaceProvider(Path providerRoot, ProjectVersionSource source) {
        this(
                providerRoot,
                source,
                LocalWorkspaceProvider::defaultMove,
                Files::write,
                LocalWorkspaceProvider::readBoundedNoFollow,
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree,
                DEFAULT_PATH_PROBE,
                NOOP_CASE_PROBE_OBSERVER);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceFileMover mover) {
        this(
                providerRoot,
                source,
                mover,
                Files::write,
                LocalWorkspaceProvider::readBoundedNoFollow,
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree,
                DEFAULT_PATH_PROBE,
                NOOP_CASE_PROBE_OBSERVER);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceBackupReader backupReader) {
        this(
                providerRoot,
                source,
                LocalWorkspaceProvider::defaultMove,
                Files::write,
                backupReader,
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree,
                DEFAULT_PATH_PROBE,
                NOOP_CASE_PROBE_OBSERVER);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceFileMover mover,
            WorkspaceMaterializationWriter materializationWriter) {
        this(
                providerRoot,
                source,
                mover,
                materializationWriter,
                LocalWorkspaceProvider::readBoundedNoFollow,
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree,
                DEFAULT_PATH_PROBE,
                NOOP_CASE_PROBE_OBSERVER);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceMaterializationWriter materializationWriter,
            WorkspaceDirectoryPublisher directoryPublisher,
            WorkspaceTreeDeleter treeDeleter) {
        this(
                providerRoot,
                source,
                LocalWorkspaceProvider::defaultMove,
                materializationWriter,
                LocalWorkspaceProvider::readBoundedNoFollow,
                directoryPublisher,
                treeDeleter,
                DEFAULT_PATH_PROBE,
                NOOP_CASE_PROBE_OBSERVER);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceFileMover mover,
            WorkspaceMaterializationWriter materializationWriter,
            WorkspaceBackupReader backupReader,
            WorkspaceDirectoryPublisher directoryPublisher,
            WorkspaceTreeDeleter treeDeleter) {
        this(
                providerRoot,
                source,
                mover,
                materializationWriter,
                backupReader,
                directoryPublisher,
                treeDeleter,
                DEFAULT_PATH_PROBE,
                NOOP_CASE_PROBE_OBSERVER);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceMaterializationWriter materializationWriter,
            WorkspaceDirectoryPublisher directoryPublisher,
            WorkspaceTreeDeleter treeDeleter,
            WorkspacePathProbe pathProbe) {
        this(
                providerRoot,
                source,
                LocalWorkspaceProvider::defaultMove,
                materializationWriter,
                LocalWorkspaceProvider::readBoundedNoFollow,
                directoryPublisher,
                treeDeleter,
                pathProbe,
                NOOP_CASE_PROBE_OBSERVER);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceCaseProbeObserver caseProbeObserver) {
        this(
                providerRoot,
                source,
                LocalWorkspaceProvider::defaultMove,
                Files::write,
                LocalWorkspaceProvider::readBoundedNoFollow,
                LocalWorkspaceProvider::defaultPublish,
                LocalWorkspaceProvider::deleteTree,
                DEFAULT_PATH_PROBE,
                caseProbeObserver);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceFileMover mover,
            WorkspaceMaterializationWriter materializationWriter,
            WorkspaceBackupReader backupReader,
            WorkspaceDirectoryPublisher directoryPublisher,
            WorkspaceTreeDeleter treeDeleter,
            WorkspacePathProbe pathProbe,
            WorkspaceCaseProbeObserver caseProbeObserver) {
        WorkspaceValues.require(providerRoot, "configureWorkspace");
        this.source = WorkspaceValues.require(source, "configureWorkspace");
        this.mover = WorkspaceValues.require(mover, "configureWorkspace");
        this.materializationWriter =
                WorkspaceValues.require(materializationWriter, "configureWorkspace");
        this.backupReader = WorkspaceValues.require(backupReader, "configureWorkspace");
        this.directoryPublisher =
                WorkspaceValues.require(directoryPublisher, "configureWorkspace");
        this.treeDeleter = WorkspaceValues.require(treeDeleter, "configureWorkspace");
        this.pathProbe = WorkspaceValues.require(pathProbe, "configureWorkspace");
        WorkspaceValues.require(caseProbeObserver, "configureWorkspace");
        if (!providerRoot.isAbsolute()) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, "configureWorkspace", null);
        }
        try {
            Path absolute = providerRoot.normalize();
            Files.createDirectories(absolute);
            this.providerRoot = absolute.toRealPath();
            requireDirectoryWithoutLinks(this.providerRoot, "configureWorkspace", null);
            this.caseSensitive = isCaseSensitive(
                    this.providerRoot,
                    caseProbeObserver);
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "configureWorkspace", null);
        }
    }

    @Override
    public synchronized VerifiedWorkspaceMaterialization materialize(
            WorkspaceMaterializationSpec spec) {
        WorkspaceValues.require(spec, "materialize");
        requireNotRetired(spec.workspaceId(), "materialize");
        WorkspaceRegistration existing = workspaces.get(spec.workspaceId());
        if (existing != null) {
            return existingMaterialization(existing, spec, "materialize");
        }
        rejectUnknownOccupancy(spec.workspaceId(), "materialize", null);

        ProjectVersionSnapshot snapshot = loadSnapshot(spec.sourceProjectVersion());
        WorkspaceManifestValidator.validateReference(
                snapshot,
                spec.sourceProjectVersion());
        WorkspaceManifestValidator.validateLimits(snapshot.files(), spec.limits());
        WorkspaceManifestValidator.validatePaths(snapshot.files(), true);
        WorkspaceManifestValidator.validateHashes(snapshot.files());
        WorkspaceManifestValidator.validatePaths(snapshot.files(), caseSensitive);
        ContentHash fingerprint = WorkspaceManifestFingerprint.calculate(snapshot);
        ContentHash pinned = sourceManifestFingerprints.get(spec.sourceProjectVersion());
        if (pinned != null && !pinned.equals(fingerprint)) {
            throw failure(
                    WorkspaceErrorCode.SOURCE_MANIFEST_FINGERPRINT_MISMATCH,
                    "materialize",
                    null);
        }
        sourceManifestFingerprints.putIfAbsent(spec.sourceProjectVersion(), fingerprint);

        Path container = containerFor(spec.workspaceId());
        Path pending = pendingFor(spec.workspaceId());
        WorkspaceRef workspace = new WorkspaceRef(
                spec.workspaceId(),
                spec.sourceProjectVersion());
        WorkspaceState state = new WorkspaceState(
                workspace,
                spec,
                null,
                pending,
                pending.resolve(DATA_DIRECTORY),
                pending.resolve(STAGING_DIRECTORY),
                spec.limits(),
                WorkspaceManifestValidator.baseline(snapshot.files()));
        MaterializationClaim claim = acquireMaterializationClaim(spec.workspaceId());
        if (claim == null) {
            throw failure(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, "materialize", null);
        }
        boolean ownsPending = false;
        try {
            rejectUnknownOccupancy(spec.workspaceId(), "materialize", claim);
            Files.createDirectory(pending);
            ownsPending = true;
            Files.createDirectory(state.dataRoot());
            Files.createDirectory(state.stagingRoot());
            for (ProjectFileSnapshot file : WorkspaceManifestValidator.sorted(snapshot.files())) {
                byte[] content = file.content();
                Path target = secureResolve(state, file.path(), false, "materialize");
                createParents(state, file.path(), "materialize");
                materializationWriter.write(
                        target,
                        content,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                WorkspaceMaterializationVerifier.verifyWrittenFile(
                        target,
                        file,
                        state.limits());
            }
            requirePendingStructure(state);
            WorkspaceMaterializationVerifier.verifyPending(
                    state.dataRoot(),
                    state.stagingRoot(),
                    snapshot.files(),
                    state.limits());
            VerifiedWorkspaceMaterialization result =
                    new VerifiedWorkspaceMaterialization(spec, fingerprint);
            requireHeldClaim(claim, "materialize");
            requirePendingStructure(state);
            rejectFinalOccupancy(spec.workspaceId(), "materialize");
            directoryPublisher.publish(pending, container);
            ownsPending = false;
            WorkspaceState active = state.published(container, result);
            workspaces.put(
                    spec.workspaceId(),
                    WorkspaceRegistration.active(active));
            return result;
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (AtomicMoveNotSupportedException exception) {
            throw failure(
                    WorkspaceErrorCode.ATOMIC_PUBLISH_NOT_SUPPORTED,
                    "materialize",
                    null);
        } catch (FileAlreadyExistsException exception) {
            rejectUnknownOccupancy(
                    spec.workspaceId(),
                    "materialize",
                    claim);
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    "materialize",
                    null);
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "materialize", null);
        } finally {
            boolean claimTransferred = false;
            if (ownsPending) {
                claimTransferred =
                        recoverOwnedPendingAfterMaterializationFailure(state, claim);
            }
            if (!claimTransferred) {
                releaseMaterializationClaim(claim);
            }
        }
    }

    @Override
    public synchronized VerifiedWorkspaceMaterialization inspectMaterialization(
            WorkspaceMaterializationSpec spec) {
        WorkspaceValues.require(spec, "inspectMaterialization");
        requireNotRetired(spec.workspaceId(), "inspectMaterialization");
        WorkspaceRegistration existing = workspaces.get(spec.workspaceId());
        if (existing != null) {
            return existingMaterialization(existing, spec, "inspectMaterialization");
        }
        return recoverPublishedMaterialization(spec);
    }

    @Override
    public synchronized List<WorkspaceFileStat> list(WorkspaceRef workspace) {
        WorkspaceState state = state(workspace, "list");
        return List.copyOf(scan(state, "list").values());
    }

    @Override
    public synchronized WorkspaceFileStat stat(WorkspaceRef workspace, ProjectPath path) {
        WorkspaceState state = state(workspace, "stat");
        WorkspaceValues.require(path, "stat");
        Path target = secureResolve(state, path, true, "stat");
        requireRegularFile(target, "stat", path);
        try {
            long size = Files.size(target);
            requireReadableSize(state, path, size, "stat");
            return new WorkspaceFileStat(
                    path,
                    size,
                    WorkspaceHashes.sha256(
                            target,
                            state.limits().maxFileBytes(),
                            "stat",
                            path));
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "stat", path);
        }
    }

    @Override
    public synchronized byte[] read(WorkspaceRef workspace, ProjectPath path) {
        WorkspaceState state = state(workspace, "read");
        WorkspaceValues.require(path, "read");
        Path target = secureResolve(state, path, true, "read");
        requireRegularFile(target, "read", path);
        try {
            long size = Files.size(target);
            requireReadableSize(state, path, size, "read");
            return readBoundedNoFollow(target, state.limits().maxFileBytes(), "read", path);
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "read", path);
        }
    }

    @Override
    public synchronized void create(WorkspaceRef workspace, ProjectPath path, byte[] content) {
        write(workspace, path, content, false);
    }

    @Override
    public synchronized void replace(WorkspaceRef workspace, ProjectPath path, byte[] content) {
        write(workspace, path, content, true);
    }

    @Override
    public synchronized void delete(WorkspaceRef workspace, ProjectPath path) {
        WorkspaceState state = state(workspace, "delete");
        WorkspaceValues.require(path, "delete");
        Path target = secureResolve(state, path, true, "delete");
        requireRegularFile(target, "delete", path);
        try {
            Files.delete(target);
            removeEmptyParents(state, target.getParent());
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "delete", path);
        }
    }

    @Override
    public synchronized void move(WorkspaceRef workspace, ProjectPath sourcePath, ProjectPath targetPath) {
        WorkspaceState state = state(workspace, "move");
        WorkspaceValues.require(sourcePath, "move");
        WorkspaceValues.require(targetPath, "move");
        if (sourcePath.equals(targetPath)) {
            return;
        }
        Path sourceFile = secureResolve(state, sourcePath, true, "move");
        requireRegularFile(sourceFile, "move", sourcePath);
        Path targetFile = secureResolve(state, targetPath, false, "move");
        if (Files.exists(targetFile, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.PATH_ALREADY_EXISTS, "move", targetPath);
        }
        ensureAdditionalFileAllowed(state, 0, false, targetPath, "move");
        createParents(state, targetPath, "move");
        try {
            mover.move(sourceFile, targetFile, false);
            requireRegularFile(targetFile, "move", targetPath);
            removeEmptyParents(state, sourceFile.getParent());
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "move", sourcePath);
        }
    }

    @Override
    public synchronized WorkspaceDiff diff(WorkspaceRef workspace, DiffId diffId, Instant createdAt) {
        WorkspaceState state = state(workspace, "diff");
        WorkspaceValues.require(diffId, "diff");
        WorkspaceValues.require(createdAt, "diff");

        Map<ProjectPath, WorkspaceFileStat> currentStats = scan(state, "diff");
        Map<ProjectPath, ContentHash> current =
                new TreeMap<>(WorkspaceDiffCalculator.pathComparator());
        currentStats.forEach((path, stat) -> current.put(path, stat.hash()));
        return new WorkspaceDiff(
                diffId,
                workspace,
                WorkspaceDiffCalculator.calculate(state.baseline(), current),
                createdAt);
    }

    @Override
    public synchronized void cleanup(WorkspaceRef workspace) {
        WorkspaceValues.require(workspace, "cleanup");
        RetiredTombstone tombstone = retiredTombstone(workspace.id());
        if (tombstone != null) {
            requireMatchingTombstone(tombstone, workspace);
            return;
        }
        WorkspaceRegistration registration = workspaces.get(workspace.id());
        if (registration == null) {
            requireNoSharedClaim(workspace.id(), "cleanup");
            requireWorkspacePathsAbsent(workspace.id(), "cleanup");
            requireNoSharedClaim(workspace.id(), "cleanup");
            return;
        }
        WorkspaceState state = registration.state();
        requireMatchingReference(state, workspace, "cleanup");
        if (registration.status() == RegistrationStatus.RETIRED) {
            return;
        }
        MaterializationClaim cleanupClaim;
        if (registration.status() == RegistrationStatus.ACTIVE) {
            cleanupClaim = acquireMaterializationClaim(workspace.id());
            if (cleanupClaim == null) {
                throw failure(
                        WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                        "cleanup",
                        null);
            }
            try {
                requireManagedState(state, "cleanup");
                rejectLinksInTree(state.container(), "cleanup");
            } catch (RuntimeException exception) {
                releaseMaterializationClaim(cleanupClaim);
                throw exception;
            }
            registration = WorkspaceRegistration.cleanupPending(
                    state,
                    cleanupClaim);
            workspaces.put(workspace.id(), registration);
        } else {
            cleanupClaim = registration.retainedClaim("cleanup");
            requireHeldClaim(cleanupClaim, "cleanup");
        }
        boolean failedMaterializationPending =
                state.container().equals(pendingFor(workspace.id()));
        try {
            if (!Files.notExists(state.container(), NOFOLLOW_LINKS)) {
                if (failedMaterializationPending) {
                    deleteOwnedPendingWithoutFollowing(state.container());
                } else {
                    if (linkLike(state.container())) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, "cleanup", null);
                    }
                    rejectLinksInTree(state.container(), "cleanup");
                    treeDeleter.delete(state.container());
                }
            }
            if (!Files.notExists(state.container(), NOFOLLOW_LINKS)) {
                throw failure(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, "cleanup", null);
            }
            registerRetiredTombstone(state, "cleanup");
            workspaces.put(workspace.id(), WorkspaceRegistration.retired(state));
            releaseMaterializationClaim(cleanupClaim);
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "cleanup", null);
        }
    }

    private void write(WorkspaceRef workspace, ProjectPath path, byte[] supplied, boolean replace) {
        String operation = replace ? "replace" : "create";
        WorkspaceState state = state(workspace, operation);
        WorkspaceValues.require(path, operation);
        WorkspaceValues.require(supplied, operation);
        if (supplied.length > state.limits().maxFileBytes()) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
        byte[] content = supplied.clone();
        Path target = secureResolve(state, path, false, operation);
        boolean exists = Files.exists(target, NOFOLLOW_LINKS);
        if (replace && !exists) {
            throw failure(WorkspaceErrorCode.PATH_NOT_FOUND, operation, path);
        }
        if (!replace && exists) {
            throw failure(WorkspaceErrorCode.PATH_ALREADY_EXISTS, operation, path);
        }
        if (exists) {
            requireRegularFile(target, operation, path);
        }
        long previousSize = exists ? fileSizeNoFollow(target, operation, path) : 0;
        ensureAdditionalFileAllowed(state, content.length - previousSize, !exists, path, operation);
        createParents(state, path, operation);

        Path temporary = stagingPath(state, path, ".tmp", operation);
        Path backup = stagingPath(state, path, ".bak", operation);
        try {
            ensureStagingAvailable(temporary, operation, path);
            ensureStagingAvailable(backup, operation, path);
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            requireRegularStagingFile(state, temporary, operation, path);
            if (exists) {
                acquirePriorFile(state, path, target, backup, operation);
            }
            try {
                requireTargetParentWithoutLinks(state, path, target, operation);
                requireRegularStagingFile(state, temporary, operation, path);
                mover.move(temporary, target, exists);
            } catch (IOException moveFailure) {
                if (exists) {
                    restorePriorFile(state, path, target, backup, operation);
                }
                throw moveFailure;
            }
            requireRegularFile(target, operation, path);
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, path);
        } finally {
            deleteRegularIfPresent(temporary);
            deleteRegularIfPresent(backup);
        }
    }

    private ProjectVersionSnapshot loadSnapshot(ProjectVersionRef sourceVersion) {
        try {
            ProjectVersionSnapshot snapshot = source.load(sourceVersion);
            if (snapshot == null) {
                throw failure(WorkspaceErrorCode.SOURCE_FAILURE, "materialize", null);
            }
            return snapshot;
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(WorkspaceErrorCode.SOURCE_FAILURE, "materialize", null);
        }
    }

    private VerifiedWorkspaceMaterialization recoverPublishedMaterialization(
            WorkspaceMaterializationSpec spec) {
        String operation = "inspectMaterialization";
        MaterializationClaim claim =
                acquireMaterializationClaim(spec.workspaceId());
        if (claim == null) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
        try {
            requireRecoverablePublishedOccupancy(spec.workspaceId(), operation);

            Path container = containerFor(spec.workspaceId());
            Path dataRoot = container.resolve(DATA_DIRECTORY);
            Path stagingRoot = container.resolve(STAGING_DIRECTORY);
            WorkspaceMaterializationVerifier.verifyActiveStructure(
                    container,
                    dataRoot,
                    stagingRoot,
                    operation);

            ProjectVersionSnapshot snapshot =
                    loadSnapshot(spec.sourceProjectVersion());
            WorkspaceManifestValidator.validateReference(
                    snapshot,
                    spec.sourceProjectVersion());
            WorkspaceManifestValidator.validateLimits(
                    snapshot.files(),
                    spec.limits());
            WorkspaceManifestValidator.validatePaths(snapshot.files(), true);
            WorkspaceManifestValidator.validateHashes(snapshot.files());
            WorkspaceManifestValidator.validatePaths(
                    snapshot.files(),
                    caseSensitive);
            ContentHash fingerprint =
                    WorkspaceManifestFingerprint.calculate(snapshot);
            ContentHash pinned =
                    sourceManifestFingerprints.get(spec.sourceProjectVersion());
            if (pinned != null && !pinned.equals(fingerprint)) {
                throw failure(
                        WorkspaceErrorCode.SOURCE_MANIFEST_FINGERPRINT_MISMATCH,
                        operation,
                        null);
            }

            WorkspaceRef workspace = new WorkspaceRef(
                    spec.workspaceId(),
                    spec.sourceProjectVersion());
            VerifiedWorkspaceMaterialization result =
                    new VerifiedWorkspaceMaterialization(spec, fingerprint);
            WorkspaceState active = new WorkspaceState(
                    workspace,
                    spec,
                    result,
                    container,
                    dataRoot,
                    stagingRoot,
                    spec.limits(),
                    WorkspaceManifestValidator.baseline(snapshot.files()));
            requireManagedState(active, operation);
            WorkspaceMaterializationVerifier.verifyPending(
                    dataRoot,
                    stagingRoot,
                    snapshot.files(),
                    spec.limits());
            requireHeldClaim(claim, operation);
            requireRecoverablePublishedOccupancy(spec.workspaceId(), operation);
            requireNotRetired(spec.workspaceId(), operation);

            sourceManifestFingerprints.putIfAbsent(
                    spec.sourceProjectVersion(),
                    fingerprint);
            workspaces.put(
                    spec.workspaceId(),
                    WorkspaceRegistration.active(active));
            return result;
        } finally {
            releaseMaterializationClaim(claim);
        }
    }

    private VerifiedWorkspaceMaterialization existingMaterialization(
            WorkspaceRegistration registration,
            WorkspaceMaterializationSpec supplied,
            String operation) {
        if (registration.status() == RegistrationStatus.CLEANUP_PENDING) {
            throw failure(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, operation, null);
        }
        if (registration.status() == RegistrationStatus.RETIRED) {
            throw failure(WorkspaceErrorCode.WORKSPACE_RETIRED, operation, null);
        }
        WorkspaceState state = registration.state();
        if (!state.spec().equals(supplied)) {
            throw failure(WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT, operation, null);
        }
        requireManagedState(state, operation);
        WorkspaceMaterializationVerifier.verifyActiveStructure(
                state.container(),
                state.dataRoot(),
                state.stagingRoot(),
                operation);
        return state.materialization();
    }

    private void rejectUnknownOccupancy(
            WorkspaceId workspaceId,
            String operation,
            MaterializationClaim ownedClaim) {
        requireNotRetired(workspaceId, operation);
        requireWorkspacePathsAbsent(workspaceId, operation);
        MaterializationClaimKey key =
                new MaterializationClaimKey(providerRoot, workspaceId);
        Object holder = MATERIALIZATION_CLAIMS.get(key);
        if (holder != null && (ownedClaim == null || holder != ownedClaim.token())) {
            throw failure(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, operation, null);
        }
        requireNotRetired(workspaceId, operation);
    }

    private void rejectFinalOccupancy(WorkspaceId workspaceId, String operation) {
        requireNotRetired(workspaceId, operation);
        Path container = containerFor(workspaceId);
        requireAbsent(container, operation);
    }

    private void requireWorkspacePathsAbsent(
            WorkspaceId workspaceId,
            String operation) {
        PathOccupancy container =
                probeOccupancy(containerFor(workspaceId));
        PathOccupancy pending =
                probeOccupancy(pendingFor(workspaceId));
        if (container == PathOccupancy.LINK
                || pending == PathOccupancy.LINK) {
            throw failure(
                    WorkspaceErrorCode.LINK_ESCAPE,
                    operation,
                    null);
        }
        if (container != PathOccupancy.ABSENT
                || pending != PathOccupancy.ABSENT) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
    }

    private void requireRecoverablePublishedOccupancy(
            WorkspaceId workspaceId,
            String operation) {
        requireNotRetired(workspaceId, operation);
        PathOccupancy container =
                probeOccupancy(containerFor(workspaceId));
        PathOccupancy pending =
                probeOccupancy(pendingFor(workspaceId));
        if (container == PathOccupancy.LINK
                || pending == PathOccupancy.LINK) {
            throw failure(
                    WorkspaceErrorCode.LINK_ESCAPE,
                    operation,
                    null);
        }
        if (pending != PathOccupancy.ABSENT) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
        if (container == PathOccupancy.ABSENT) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                    operation,
                    null);
        }
        if (container != PathOccupancy.PRESENT) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
        requireNotRetired(workspaceId, operation);
    }

    private void requireAbsent(Path path, String operation) {
        PathOccupancy occupancy = probeOccupancy(path);
        if (occupancy == PathOccupancy.LINK) {
            throw failure(
                    WorkspaceErrorCode.LINK_ESCAPE,
                    operation,
                    null);
        }
        if (occupancy != PathOccupancy.ABSENT) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
    }

    private PathOccupancy probeOccupancy(Path path) {
        boolean exists;
        boolean notExists;
        try {
            exists = pathProbe.exists(path);
            notExists = pathProbe.notExists(path);
        } catch (RuntimeException exception) {
            return PathOccupancy.INDETERMINATE;
        }
        if (!exists && notExists) {
            return PathOccupancy.ABSENT;
        }
        if (!exists || notExists) {
            return PathOccupancy.INDETERMINATE;
        }
        try {
            BasicFileAttributes attributes =
                    pathProbe.readAttributes(path);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                return PathOccupancy.LINK;
            }
            return PathOccupancy.PRESENT;
        } catch (IOException | RuntimeException exception) {
            return PathOccupancy.INDETERMINATE;
        }
    }

    private MaterializationClaim acquireMaterializationClaim(WorkspaceId workspaceId) {
        MaterializationClaimKey key = claimKey(workspaceId);
        Object token = new Object();
        return MATERIALIZATION_CLAIMS.putIfAbsent(key, token) == null
                ? new MaterializationClaim(key, token)
                : null;
    }

    private static void requireHeldClaim(
            MaterializationClaim claim,
            String operation) {
        if (MATERIALIZATION_CLAIMS.get(claim.key()) != claim.token()) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
    }

    private static void releaseMaterializationClaim(MaterializationClaim claim) {
        MATERIALIZATION_CLAIMS.remove(claim.key(), claim.token());
    }

    private MaterializationClaimKey claimKey(WorkspaceId workspaceId) {
        return new MaterializationClaimKey(providerRoot, workspaceId);
    }

    private RetiredTombstone retiredTombstone(WorkspaceId workspaceId) {
        return RETIRED_TOMBSTONES.get(claimKey(workspaceId));
    }

    private void requireNotRetired(
            WorkspaceId workspaceId,
            String operation) {
        if (retiredTombstone(workspaceId) != null) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    operation,
                    null);
        }
    }

    private void requireNoSharedClaim(
            WorkspaceId workspaceId,
            String operation) {
        requireNotRetired(workspaceId, operation);
        if (MATERIALIZATION_CLAIMS.containsKey(claimKey(workspaceId))) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
        requireNotRetired(workspaceId, operation);
    }

    private void registerRetiredTombstone(
            WorkspaceState state,
            String operation) {
        RetiredTombstone candidate = new RetiredTombstone(
                state.spec(),
                state.workspace());
        RetiredTombstone existing = RETIRED_TOMBSTONES.putIfAbsent(
                claimKey(state.workspace().id()),
                candidate);
        if (existing != null && !existing.equals(candidate)) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null);
        }
    }

    private static void requireMatchingTombstone(
            RetiredTombstone tombstone,
            WorkspaceRef supplied) {
        if (!tombstone.workspace().equals(supplied)) {
            throw failure(
                    WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH,
                    "cleanup",
                    null);
        }
    }

    private void requirePendingStructure(WorkspaceState state) {
        if (!state.container().equals(pendingFor(state.workspace().id()))
                || !state.container().getParent().equals(providerRoot)
                || !state.dataRoot().getParent().equals(state.container())
                || !state.stagingRoot().getParent().equals(state.container())) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, "materialize", null);
        }
        requirePendingDirectory(state.container());
        requirePendingDirectory(state.dataRoot());
        requirePendingDirectory(state.stagingRoot());
    }

    private static void requirePendingDirectory(Path path) {
        if (linkLike(path)) {
            throw failure(
                    WorkspaceErrorCode.LINK_ESCAPE,
                    "materialize",
                    null);
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    "materialize",
                    null);
        }
    }

    private WorkspaceState state(WorkspaceRef workspace, String operation) {
        WorkspaceValues.require(workspace, operation);
        requireNotRetired(workspace.id(), operation);
        WorkspaceRegistration registration = workspaces.get(workspace.id());
        if (registration == null) {
            requireNoSharedClaim(workspace.id(), operation);
            requireWorkspacePathsAbsent(workspace.id(), operation);
            requireNoSharedClaim(workspace.id(), operation);
            throw failure(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, operation, null);
        }
        WorkspaceState state = registration.state();
        requireMatchingReference(state, workspace, operation);
        if (registration.status() == RegistrationStatus.RETIRED) {
            throw failure(WorkspaceErrorCode.WORKSPACE_RETIRED, operation, null);
        }
        if (registration.status() == RegistrationStatus.CLEANUP_PENDING) {
            throw failure(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, operation, null);
        }
        requireManagedState(state, operation);
        return state;
    }

    private static void requireMatchingReference(
            WorkspaceState state,
            WorkspaceRef supplied,
            String operation) {
        if (!state.workspace().equals(supplied)) {
            throw failure(WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH, operation, null);
        }
    }

    private void requireManagedState(WorkspaceState state, String operation) {
        if (!state.container().getParent().equals(providerRoot)
                || !state.dataRoot().getParent().equals(state.container())
                || !state.stagingRoot().getParent().equals(state.container())
                || !state.container().equals(containerFor(state.workspace().id()))) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, null);
        }
        requireRegisteredDirectory(providerRoot, operation);
        requireRegisteredDirectory(state.container(), operation);
        requireRegisteredDirectory(state.dataRoot(), operation);
        requireRegisteredDirectory(state.stagingRoot(), operation);
        try {
            if (!providerRoot.equals(providerRoot.toRealPath())
                    || !state.container().toRealPath().startsWith(providerRoot)
                    || !state.dataRoot().toRealPath().startsWith(state.container().toRealPath())
                    || !state.stagingRoot().toRealPath().startsWith(state.container().toRealPath())) {
                throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, null);
            }
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, operation, null);
        }
    }

    private static void requireRegisteredDirectory(Path path, String operation) {
        if (linkLike(path)) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, null);
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, operation, null);
        }
    }

    private Path secureResolve(
            WorkspaceState state,
            ProjectPath projectPath,
            boolean mustExist,
            String operation) {
        WorkspaceManifestValidator.validatePortablePath(projectPath, operation);
        Path resolved = state.dataRoot();
        String[] segments = projectPath.value().split("/");
        for (int index = 0; index < segments.length; index++) {
            resolved = resolved.resolve(segments[index]);
            if (!resolved.normalize().startsWith(state.dataRoot())) {
                throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, projectPath);
            }
            if (Files.exists(resolved, NOFOLLOW_LINKS)) {
                requireNotLink(resolved, operation, projectPath);
                if (index < segments.length - 1 && !Files.isDirectory(resolved, NOFOLLOW_LINKS)) {
                    throw failure(WorkspaceErrorCode.NOT_REGULAR_FILE, operation, projectPath);
                }
            }
        }
        if (mustExist && !Files.exists(resolved, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.PATH_NOT_FOUND, operation, projectPath);
        }
        return resolved;
    }

    private static void createParents(
            WorkspaceState state,
            ProjectPath path,
            String operation) {
        Path current = state.dataRoot();
        String[] segments = path.value().split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            current = current.resolve(segments[index]);
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                requireNotLink(current, operation, path);
                if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                    throw failure(WorkspaceErrorCode.PATH_COLLISION, operation, path);
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException exception) {
                    requireNotLink(current, operation, path);
                    if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                        throw failure(WorkspaceErrorCode.PATH_COLLISION, operation, path);
                    }
                } catch (IOException exception) {
                    throw failure(WorkspaceErrorCode.IO_FAILURE, operation, path);
                }
            }
        }
    }

    private Map<ProjectPath, WorkspaceFileStat> scan(WorkspaceState state, String operation) {
        TreeMap<ProjectPath, WorkspaceFileStat> result =
                new TreeMap<>(WorkspaceDiffCalculator.pathComparator());
        long[] aggregate = {0};
        int[] count = {0};
        try {
            Files.walkFileTree(state.dataRoot(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(state.dataRoot()) && linkLike(directory)) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, relative(state, directory));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    ProjectPath path = relative(state, file);
                    if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, path);
                    }
                    long size = attributes.size();
                    requireReadableSize(state, path, size, operation);
                    count[0]++;
                    if (count[0] > state.limits().maxFiles()) {
                        throw failure(WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED, operation, path);
                    }
                    try {
                        aggregate[0] = Math.addExact(aggregate[0], size);
                    } catch (ArithmeticException exception) {
                        throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, operation, path);
                    }
                    if (aggregate[0] > state.limits().maxAggregateBytes()) {
                        throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, operation, path);
                    }
                    result.put(path, new WorkspaceFileStat(
                            path,
                            size,
                            WorkspaceHashes.sha256(
                                    file,
                                    state.limits().maxFileBytes(),
                                    operation,
                                    path)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, null);
        }
    }

    private void ensureAdditionalFileAllowed(
            WorkspaceState state,
            long aggregateDelta,
            boolean newFile,
            ProjectPath path,
            String operation) {
        Collection<WorkspaceFileStat> current = scan(state, operation).values();
        if (newFile && current.size() >= state.limits().maxFiles()) {
            throw failure(WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED, operation, path);
        }
        long aggregate = current.stream().mapToLong(WorkspaceFileStat::size).sum();
        if (aggregateDelta > 0 && aggregate > state.limits().maxAggregateBytes() - aggregateDelta) {
            throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, operation, path);
        }
    }

    private Path stagingPath(
            WorkspaceState state,
            ProjectPath path,
            String suffix,
            String operation) {
        requireDirectoryWithoutLinks(state.stagingRoot(), operation, path);
        return state.stagingRoot().resolve(WorkspaceHashes.sha256Text(path.value()) + suffix);
    }

    private static void ensureStagingAvailable(Path path, String operation, ProjectPath projectPath) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.TEMPORARY_PATH_OCCUPIED, operation, projectPath);
        }
    }

    private void acquirePriorFile(
            WorkspaceState state,
            ProjectPath path,
            Path target,
            Path backup,
            String operation) throws IOException {
        requireTargetParentWithoutLinks(state, path, target, operation);
        requireRegularFile(target, operation, path);
        byte[] priorContent =
                backupReader.read(target, state.limits().maxFileBytes(), operation, path);
        if (priorContent.length > state.limits().maxFileBytes()) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
        requireDirectoryWithoutLinks(state.stagingRoot(), operation, path);
        ensureStagingAvailable(backup, operation, path);
        Files.write(
                backup,
                priorContent,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        requireRegularStagingFile(state, backup, operation, path);
    }

    private void restorePriorFile(
            WorkspaceState state,
            ProjectPath path,
            Path target,
            Path backup,
            String operation) {
        if (!Files.exists(backup, NOFOLLOW_LINKS)) {
            return;
        }
        try {
            requireTargetParentWithoutLinks(state, path, target, operation);
            requireRegularStagingFile(state, backup, operation, path);
            /*
             * Moving with REPLACE_EXISTING replaces a final symbolic-link entry
             * instead of opening its referent. Parent components are rechecked
             * immediately above; a hostile cross-process swap after that check
             * remains a filesystem boundary this JDK-only provider cannot close.
             */
            defaultMove(backup, target, true);
            requireRegularFile(target, operation, path);
        } catch (WorkspaceException | IOException ignored) {
            // The public failure remains stable and does not expose a host path.
        }
    }

    private void requireTargetParentWithoutLinks(
            WorkspaceState state,
            ProjectPath path,
            Path target,
            String operation) {
        requireManagedState(state, operation);
        Path parent = state.dataRoot();
        String[] segments = path.value().split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            parent = parent.resolve(segments[index]);
            requireNotLink(parent, operation, path);
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) {
                throw failure(WorkspaceErrorCode.PATH_COLLISION, operation, path);
            }
        }
        if (!target.getParent().equals(parent)) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, path);
        }
    }

    private static void requireRegularStagingFile(
            WorkspaceState state,
            Path path,
            String operation,
            ProjectPath projectPath) {
        requireDirectoryWithoutLinks(state.stagingRoot(), operation, projectPath);
        if (!path.getParent().equals(state.stagingRoot())) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, projectPath);
        }
        requireRegularFile(path, operation, projectPath);
    }

    private static void removeEmptyParents(WorkspaceState state, Path start) {
        Path current = start;
        while (current != null && !current.equals(state.dataRoot())) {
            requireNotLink(current, "cleanupDirectories", null);
            try {
                Files.delete(current);
                current = current.getParent();
            } catch (DirectoryNotEmptyException exception) {
                return;
            } catch (IOException exception) {
                throw failure(WorkspaceErrorCode.IO_FAILURE, "cleanupDirectories", null);
            }
        }
    }

    private static void requireReadableSize(
            WorkspaceState state,
            ProjectPath path,
            long size,
            String operation) {
        if (size > state.limits().maxFileBytes()) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
        if (size > Integer.MAX_VALUE) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
    }

    private static long fileSizeNoFollow(
            Path path,
            String operation,
            ProjectPath projectPath) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
            }
            if (!attributes.isRegularFile()) {
                throw failure(WorkspaceErrorCode.NOT_REGULAR_FILE, operation, projectPath);
            }
            return attributes.size();
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, projectPath);
        }
    }

    private static void requireRegularFile(Path path, String operation, ProjectPath projectPath) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.PATH_NOT_FOUND, operation, projectPath);
        }
        requireNotLink(path, operation, projectPath);
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.NOT_REGULAR_FILE, operation, projectPath);
        }
    }

    private static void requireDirectoryWithoutLinks(
            Path path,
            String operation,
            ProjectPath projectPath) {
        if (linkLike(path)) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, projectPath);
        }
    }

    private static void requireNotLink(Path path, String operation, ProjectPath projectPath) {
        if (linkLike(path)) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
        }
    }

    private static boolean linkLike(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            return attributes.isOther();
        } catch (IOException exception) {
            return false;
        }
    }

    private void rejectLinksInTree(Path container, String operation) {
        try {
            Files.walkFileTree(container, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (linkLike(directory)) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, null);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, null);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, null);
        }
    }

    static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean recoverOwnedPendingAfterMaterializationFailure(
            WorkspaceState state,
            MaterializationClaim claim) {
        boolean definitelyAbsent = definitelyAbsent(state.container());
        if (!definitelyAbsent) {
            try {
                deleteOwnedPendingWithoutFollowing(state.container());
            } catch (IOException | RuntimeException ignored) {
                // The materialization failure remains authoritative.
            }
            definitelyAbsent = definitelyAbsent(state.container());
        }
        if (!definitelyAbsent) {
            workspaces.put(
                    state.workspace().id(),
                    WorkspaceRegistration.cleanupPending(state, claim));
            return true;
        }
        return false;
    }

    private static boolean definitelyAbsent(Path path) {
        return noThrowAbsenceProbe(
                () -> Files.notExists(path, NOFOLLOW_LINKS));
    }

    static boolean noThrowAbsenceProbe(BooleanSupplier absenceProbe) {
        try {
            return absenceProbe.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void deleteOwnedPendingWithoutFollowing(Path pending)
            throws IOException {
        removeLinkEntriesWithoutFollowing(pending);
        if (!Files.notExists(pending, NOFOLLOW_LINKS)) {
            treeDeleter.delete(pending);
        }
    }

    private static void removeLinkEntriesWithoutFollowing(Path root)
            throws IOException {
        if (linkLike(root)) {
            Files.delete(root);
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes)
                    throws IOException {
                if (linkLike(directory)) {
                    Files.delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRegularIfPresent(Path path) {
        try {
            if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A later operation will reject an occupied staging path deterministically.
        }
    }

    private Path containerFor(WorkspaceId id) {
        String directoryName = "ws-" + WorkspaceHashes.sha256Text(id.value());
        Path container = providerRoot.resolve(directoryName).normalize();
        if (!container.getParent().equals(providerRoot)) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, "resolveWorkspace", null);
        }
        return container;
    }

    private Path pendingFor(WorkspaceId id) {
        String directoryName = "pending-" + WorkspaceHashes.sha256Text(id.value());
        Path pending = providerRoot.resolve(directoryName).normalize();
        if (!pending.getParent().equals(providerRoot)) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, "resolveWorkspace", null);
        }
        return pending;
    }

    private static ProjectPath relative(WorkspaceState state, Path path) {
        Path relative = state.dataRoot().relativize(path);
        String value = relative.toString().replace(path.getFileSystem().getSeparator(), "/");
        return new ProjectPath(value);
    }

    private static boolean isCaseSensitive(
            Path providerRoot,
            WorkspaceCaseProbeObserver observer)
            throws IOException {
        Path probeDirectory = Files.createTempDirectory(
                providerRoot,
                ".paperagent-case-probe-");
        Path lower = probeDirectory.resolve("probe");
        Path upper = probeDirectory.resolve("PROBE");
        boolean lowerCreated = false;
        try {
            Files.write(
                    lower,
                    new byte[]{0},
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            lowerCreated = true;
            observer.afterLowerCreated(probeDirectory);
            boolean upperExists = Files.exists(upper, NOFOLLOW_LINKS);
            boolean upperNotExists = Files.notExists(upper, NOFOLLOW_LINKS);
            if (!upperExists && upperNotExists) {
                return true;
            }
            if (!upperExists || upperNotExists) {
                throw new IOException("case-sensitivity probe was indeterminate");
            }
            return !Files.isSameFile(lower, upper);
        } finally {
            if (lowerCreated) {
                Files.delete(lower);
            }
            Files.delete(probeDirectory);
        }
    }

    static byte[] readBoundedNoFollow(
            Path path,
            long maximum,
            String operation,
            ProjectPath projectPath) {
        if (maximum > Integer.MAX_VALUE) {
            maximum = Integer.MAX_VALUE;
        }
        try (InputStream input = Files.newInputStream(
                path,
                StandardOpenOption.READ,
                NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > maximum - read) {
                    throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, projectPath);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (UnsupportedOperationException exception) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
        } catch (IOException exception) {
            throw failure(
                    linkLike(path)
                            ? WorkspaceErrorCode.LINK_ESCAPE
                            : WorkspaceErrorCode.IO_FAILURE,
                    operation,
                    projectPath);
        }
    }

    private static void defaultMove(Path source, Path target, boolean replace) throws IOException {
        List<StandardCopyOption> options = new ArrayList<>();
        options.add(StandardCopyOption.ATOMIC_MOVE);
        if (replace) {
            options.add(StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(source, target, options.toArray(StandardCopyOption[]::new));
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    static void defaultPublish(Path pending, Path target) throws IOException {
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static WorkspaceException failure(
            WorkspaceErrorCode code,
            String operation,
            ProjectPath projectPath) {
        return new WorkspaceException(code, operation, projectPath);
    }

    private record WorkspaceState(
            WorkspaceRef workspace,
            WorkspaceMaterializationSpec spec,
            VerifiedWorkspaceMaterialization materialization,
            Path container,
            Path dataRoot,
            Path stagingRoot,
            WorkspaceMaterializationLimits limits,
            Map<ProjectPath, ContentHash> baseline) {
        private WorkspaceState {
            baseline = Map.copyOf(baseline);
        }

        private WorkspaceState published(
                Path publishedContainer,
                VerifiedWorkspaceMaterialization verifiedMaterialization) {
            return new WorkspaceState(
                    workspace,
                    spec,
                    verifiedMaterialization,
                    publishedContainer,
                    publishedContainer.resolve(DATA_DIRECTORY),
                    publishedContainer.resolve(STAGING_DIRECTORY),
                    limits,
                    baseline);
        }
    }

    private record WorkspaceRegistration(
            RegistrationStatus status,
            WorkspaceState state,
            Optional<MaterializationClaim> retainedClaim) {
        private WorkspaceRegistration {
            if (status == null || state == null || retainedClaim == null) {
                throw new IllegalArgumentException(
                        "workspace registration components are required");
            }
            boolean mustRetain =
                    status == RegistrationStatus.CLEANUP_PENDING;
            if (mustRetain != retainedClaim.isPresent()) {
                throw new IllegalArgumentException(
                        "cleanup-pending claim invariant violated");
            }
        }

        private static WorkspaceRegistration active(WorkspaceState state) {
            return new WorkspaceRegistration(
                    RegistrationStatus.ACTIVE,
                    state,
                    Optional.empty());
        }

        private static WorkspaceRegistration cleanupPending(
                WorkspaceState state,
                MaterializationClaim claim) {
            return new WorkspaceRegistration(
                    RegistrationStatus.CLEANUP_PENDING,
                    state,
                    Optional.of(claim));
        }

        private static WorkspaceRegistration retired(WorkspaceState state) {
            return new WorkspaceRegistration(
                    RegistrationStatus.RETIRED,
                    state,
                    Optional.empty());
        }

        private MaterializationClaim retainedClaim(String operation) {
            return retainedClaim.orElseThrow(() -> failure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation,
                    null));
        }
    }

    private record MaterializationClaimKey(
            Path providerRoot,
            WorkspaceId workspaceId) {
    }

    private record MaterializationClaim(
            MaterializationClaimKey key,
            Object token) {
    }

    private record RetiredTombstone(
            WorkspaceMaterializationSpec spec,
            WorkspaceRef workspace) {
    }

    interface WorkspacePathProbe {
        boolean exists(Path path);

        boolean notExists(Path path);

        BasicFileAttributes readAttributes(Path path) throws IOException;
    }

    @FunctionalInterface
    interface WorkspaceCaseProbeObserver {
        void afterLowerCreated(Path probeDirectory);
    }

    private enum PathOccupancy {
        ABSENT,
        PRESENT,
        LINK,
        INDETERMINATE
    }

    private enum RegistrationStatus {
        ACTIVE,
        CLEANUP_PENDING,
        RETIRED
    }
}
