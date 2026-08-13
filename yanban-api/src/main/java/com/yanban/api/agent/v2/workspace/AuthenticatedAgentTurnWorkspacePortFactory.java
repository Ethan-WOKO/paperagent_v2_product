package com.yanban.api.agent.v2.workspace;

import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.chain.effect.ChainActionWorkspaceAuthority;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.LocalWorkspaceProvider;
import io.paperagent.v2.workspace.ProjectFileSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSource;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspaceException;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates a local V2 Workspace boundary whose Project source is bound to one
 * authenticated Agent turn.
 */
@Service
public final class AuthenticatedAgentTurnWorkspacePortFactory {

    private final AuthenticatedAgentTurnProjectVersionSourceFactory sources;
    private final Path workspaceRoot;
    private final ConcurrentMap<WorkspaceKey, WorkspacePort> activeTurns =
            new ConcurrentHashMap<>();

    AuthenticatedAgentTurnWorkspacePortFactory(
            AuthenticatedAgentTurnProjectVersionSourceFactory sources,
            @Value("${yanban.agent.v2.workspace.root:data/agent-v2-workspaces}")
                    String configuredRoot
    ) {
        this.sources = sources;
        this.workspaceRoot = resolveRoot(configuredRoot);
    }

    public WorkspacePort create(Long authenticatedUserId, Long turnId) {
        WorkspaceKey key = new WorkspaceKey(authenticatedUserId, turnId);
        return activeTurns.computeIfAbsent(key, ignored -> {
            ProjectVersionSource source = sources.create(
                    authenticatedUserId, turnId);
            return create(source);
        });
    }

    WorkspacePort create(VerifiedAgentTurnProductContext context) {
        WorkspaceKey key = workspaceKey(context);
        return activeTurns.computeIfAbsent(
                key,
                ignored -> create(sources.create(context)));
    }

    /**
     * Reconstructs a clean Workspace for one formal chain action. Every call
     * starts from the immutable ProjectVersion plus the exact base Candidate
     * overlay, so prior action mutations cannot leak into this action.
     */
    public WorkspacePort createChain(
            VerifiedAgentTurnProductContext context,
            ChainActionWorkspaceAuthority authority) {
        workspaceKey(context);
        if (authority == null
                || context.projectVersionId().isEmpty()
                || !context.projectVersionId().orElseThrow().equals(
                authority.baseCandidate().baseProjectVersion())) {
            throw new IllegalArgumentException(
                    "chain Workspace base authority is invalid");
        }
        ProjectVersionSource project = sources.create(context);
        ProjectVersionSource baseline = version -> overlay(
                project.load(version), authority);
        String actionKey = sha256(
                authority.actionId() + "\0"
                        + authority.versionFenceSha256() + "\0"
                        + authority.workspaceId() + "\0"
                        + authority.baseCandidate().candidateIdentity() + "\0"
                        + String.valueOf(
                        authority.baseCandidate().artifactId()) + "\0"
                        + authority.baseCandidate().overlayDigestSha256() + "\0"
                        + String.join("\0", authority.readScopes()) + "\0"
                        + String.join("\0", authority.writeScopes()));
        Path actionRoot = workspaceRoot.resolve("chain-actions")
                .resolve(actionKey).resolve(UUID.randomUUID().toString());
        return new ScopedWorkspacePort(
                create(actionRoot, baseline), authority);
    }

    private static WorkspaceKey workspaceKey(
            VerifiedAgentTurnProductContext context
    ) {
        if (context == null
                || !"AGENT_TURN".equals(context.identity().source())) {
            throw new IllegalArgumentException(
                    "authenticated Workspace identity is invalid");
        }
        try {
            return new WorkspaceKey(
                    context.identity().userId(),
                    Long.valueOf(context.identity().sourceId()));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "authenticated Workspace identity is invalid");
        }
    }

    private WorkspacePort create(ProjectVersionSource source) {
        return create(workspaceRoot, source);
    }

    private WorkspacePort create(Path root, ProjectVersionSource source) {
        try {
            return new LocalWorkspaceProvider(root, source);
        } catch (RuntimeException failure) {
            throw invalidRoot();
        }
    }

    private static ProjectVersionSnapshot overlay(
            ProjectVersionSnapshot project,
            ChainActionWorkspaceAuthority authority) {
        ChainActionWorkspaceAuthority.BaseCandidateAuthority base =
                authority.baseCandidate();
        if (project == null
                || !project.version().versionId().equals(
                base.baseProjectVersion())) {
            throw new IllegalArgumentException(
                    "chain Workspace ProjectVersion changed");
        }
        Map<String, ProjectFileSnapshot> files = new LinkedHashMap<>();
        Map<String, String> foldedPaths = new LinkedHashMap<>();
        for (ProjectFileSnapshot file : project.files()) {
            String path = file.path().value();
            if (files.put(path, file) != null
                    || foldedPaths.putIfAbsent(
                    path.toLowerCase(Locale.ROOT), path) != null) {
                throw new IllegalArgumentException(
                        "chain Workspace source paths are duplicated");
            }
        }
        for (ChainActionWorkspaceAuthority.TypedChange change
                : base.changes()) {
            ProjectPath path = new ProjectPath(change.path());
            String folded = path.value().toLowerCase(Locale.ROOT);
            String existingPath = foldedPaths.get(folded);
            ProjectFileSnapshot original = existingPath == null
                    ? null : files.get(existingPath);
            if (change.type()
                    == ChainActionWorkspaceAuthority.ChangeType.ADD) {
                requireOverlay(existingPath == null,
                        "chain Candidate ADD path already exists");
                byte[] content = verifiedResult(change);
                files.put(path.value(), new ProjectFileSnapshot(
                        path, content,
                        new ContentHash("sha256", change.resultSha256()),
                        Map.of()));
                foldedPaths.put(folded, path.value());
                continue;
            }
            requireOverlay(path.value().equals(existingPath)
                            && original != null,
                    "chain Candidate source path is missing or changed case");
            String actualBase = sha256(original.content());
            requireOverlay(original.hash().algorithm().equals("sha256")
                            && original.hash().value().equals(actualBase)
                            && actualBase.equals(change.baseSha256()),
                    "chain Candidate source hash drifted");
            if (change.type()
                    == ChainActionWorkspaceAuthority.ChangeType.MODIFY) {
                byte[] content = verifiedResult(change);
                files.put(path.value(), new ProjectFileSnapshot(
                        path, content,
                        new ContentHash("sha256", change.resultSha256()),
                        original.metadata()));
            } else {
                files.remove(path.value());
                foldedPaths.remove(folded);
            }
        }
        validateScopes(authority, files);
        return new ProjectVersionSnapshot(
                project.version(),
                files.values().stream()
                        .sorted(Comparator.comparing(
                                value -> value.path().value()))
                        .toList(),
                project.metadata());
    }

    private static byte[] verifiedResult(
            ChainActionWorkspaceAuthority.TypedChange change) {
        byte[] content = change.text().getBytes(StandardCharsets.UTF_8);
        requireOverlay(sha256(content).equals(change.resultSha256()),
                "chain Candidate result hash drifted");
        return content;
    }

    private static void validateScopes(
            ChainActionWorkspaceAuthority authority,
            Map<String, ProjectFileSnapshot> files) {
        Map<String, String> folded = new LinkedHashMap<>();
        for (String path : files.keySet()) {
            requireOverlay(folded.putIfAbsent(
                    path.toLowerCase(Locale.ROOT), path) == null,
                    "chain Workspace effective paths conflict");
        }
        for (String path : authority.readScopes()) {
            requireOverlay(files.containsKey(path),
                    "chain Workspace read scope is missing");
        }
        for (String path : authority.writeScopes()) {
            if (files.containsKey(path)) continue;
            requireOverlay(!folded.containsKey(path.toLowerCase(Locale.ROOT)),
                    "chain Workspace write scope conflicts after case folding");
            if (path.contains("/")) continue;
            String basename = path.substring(path.lastIndexOf('/') + 1);
            long aliases = files.keySet().stream().filter(existing ->
                    existing.substring(existing.lastIndexOf('/') + 1)
                            .equalsIgnoreCase(basename)).count();
            requireOverlay(aliases == 0,
                    "chain Workspace write scope is missing or ambiguous");
        }
    }

    private static void requireOverlay(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Path resolveRoot(String configuredRoot) {
        try {
            if (configuredRoot == null || configuredRoot.isBlank()) {
                throw invalidRoot();
            }
            Path configured = Path.of(configuredRoot);
            Path absolute = configured.isAbsolute()
                    ? configured
                    : Path.of("").toAbsolutePath().resolve(configured);
            return absolute.normalize();
        } catch (AuthenticatedAgentTurnWorkspaceConfigurationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalidRoot();
        }
    }

    private static AuthenticatedAgentTurnWorkspaceConfigurationException invalidRoot() {
        return new AuthenticatedAgentTurnWorkspaceConfigurationException(
                AuthenticatedAgentTurnWorkspaceConfigurationException.Code.INVALID_ROOT);
    }

    /** Enforces exact formal file scopes around one action Workspace. */
    private static final class ScopedWorkspacePort implements WorkspacePort {
        private final WorkspacePort delegate;
        private final String workspaceId;
        private final String projectVersion;
        private final Set<String> readable;
        private final Set<String> writable;

        private ScopedWorkspacePort(
                WorkspacePort delegate,
                ChainActionWorkspaceAuthority authority) {
            this.delegate = delegate;
            this.workspaceId = authority.workspaceId();
            this.projectVersion = authority.baseCandidate()
                    .baseProjectVersion();
            LinkedHashSet<String> visible = new LinkedHashSet<>(
                    authority.readScopes());
            visible.addAll(authority.writeScopes());
            this.readable = Set.copyOf(visible);
            this.writable = Set.copyOf(authority.writeScopes());
        }

        @Override
        public VerifiedWorkspaceMaterialization materialize(
                WorkspaceMaterializationSpec spec) {
            requireSpec(spec, "materialize");
            return delegate.materialize(spec);
        }

        @Override
        public VerifiedWorkspaceMaterialization inspectMaterialization(
                WorkspaceMaterializationSpec spec) {
            requireSpec(spec, "inspectMaterialization");
            return delegate.inspectMaterialization(spec);
        }

        @Override
        public List<WorkspaceFileStat> list(WorkspaceRef workspace) {
            return delegate.list(workspace).stream()
                    .filter(stat -> readable.contains(stat.path().value()))
                    .toList();
        }

        @Override
        public WorkspaceFileStat stat(
                WorkspaceRef workspace, ProjectPath path) {
            requireReadable(path, "stat");
            return delegate.stat(workspace, path);
        }

        @Override
        public byte[] read(WorkspaceRef workspace, ProjectPath path) {
            requireReadable(path, "read");
            return delegate.read(workspace, path);
        }

        @Override
        public void create(
                WorkspaceRef workspace, ProjectPath path, byte[] content) {
            requireWritable(path, "create");
            delegate.create(workspace, path, content);
        }

        @Override
        public void replace(
                WorkspaceRef workspace, ProjectPath path, byte[] content) {
            requireWritable(path, "replace");
            delegate.replace(workspace, path, content);
        }

        @Override
        public void delete(WorkspaceRef workspace, ProjectPath path) {
            requireWritable(path, "delete");
            delegate.delete(workspace, path);
        }

        @Override
        public void move(
                WorkspaceRef workspace, ProjectPath source,
                ProjectPath target) {
            requireWritable(source, "move");
            requireWritable(target, "move");
            delegate.move(workspace, source, target);
        }

        @Override
        public WorkspaceDiff diff(
                WorkspaceRef workspace, DiffId diffId, Instant createdAt) {
            WorkspaceDiff diff = delegate.diff(workspace, diffId, createdAt);
            for (var entry : diff.entries()) {
                requireWritable(entry.path(), "diff");
                entry.targetPath().ifPresent(
                        target -> requireWritable(target, "diff"));
            }
            return diff;
        }

        @Override
        public void cleanup(WorkspaceRef workspace) {
            delegate.cleanup(workspace);
        }

        private void requireReadable(ProjectPath path, String operation) {
            requirePath(path, readable, operation);
        }

        private void requireWritable(ProjectPath path, String operation) {
            requirePath(path, writable, operation);
        }

        private void requireSpec(
                WorkspaceMaterializationSpec spec, String operation) {
            if (spec == null) {
                throw new WorkspaceException(
                        WorkspaceErrorCode.REQUIRED_VALUE_MISSING, operation);
            }
            if (!workspaceId.equals(spec.workspaceId().value())
                    || !projectVersion.equals(
                    spec.sourceProjectVersion().versionId())) {
                throw new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
                        operation);
            }
        }

        private static void requirePath(
                ProjectPath path, Set<String> scopes, String operation) {
            if (path == null) {
                throw new WorkspaceException(
                        WorkspaceErrorCode.REQUIRED_VALUE_MISSING, operation);
            }
            if (!scopes.contains(path.value())) {
                throw new WorkspaceException(
                        WorkspaceErrorCode.PATH_ESCAPE, operation, path);
            }
        }
    }

    private record WorkspaceKey(Long userId, Long turnId) {
        private WorkspaceKey {
            if (userId == null || userId <= 0
                    || turnId == null || turnId <= 0) {
                throw new IllegalArgumentException(
                        "authenticated Workspace identity is invalid");
            }
        }
    }
}
