package com.yanban.api.agent.v2.chain.effect;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Reconstructs one action's exact immutable Project plus base-Candidate cut. */
@Component
public final class ProductChainActionWorkspaceAuthorityFactory {
    private final ChainWorkflowRepository workflow;
    private final CandidateChangeArtifactService candidates;
    private final ProjectService projects;

    public ProductChainActionWorkspaceAuthorityFactory(
            ChainWorkflowRepository workflow,
            CandidateChangeArtifactService candidates,
            ProjectService projects) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    public ChainActionWorkspaceAuthority create(
            TaskRecord task,
            ChainEffectRuntime.FrozenMutation action,
            List<String> readScopes,
            List<String> writeScopes) {
        return create(task, action, readScopes, writeScopes, false);
    }

    public ChainActionWorkspaceAuthority create(
            TaskRecord task,
            ChainEffectRuntime.FrozenMutation action,
            List<String> readScopes,
            List<String> writeScopes,
            boolean completeReadWorkspace) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(action, "action");
        require(action.sourceKind()
                        == ChainEffectRuntime.SourceKind.TOOL_ACTION
                        && task.taskId().equals(action.taskId())
                        && task.projectId() != null
                        && task.initialProjectVersion() != null,
                "chain action requires one frozen Project");
        var manifest = projects.manifest(task.userId(), task.projectId());
        require(manifest != null
                        && Objects.equals(manifest.projectId(),
                        task.projectId())
                        && Objects.equals(manifest.version(),
                        task.initialProjectVersion()),
                "chain action ProjectVersion is no longer current");
        ChainActionWorkspaceAuthority.BaseCandidateAuthority base =
                baseCandidate(task, action);
        List<String> postCandidatePaths = postCandidatePaths(
                manifest.files(), base.changes());
        List<String> effectiveReadScopes = completeReadWorkspace
                ? postCandidatePaths : readScopes;
        return new ChainActionWorkspaceAuthority(
                action.actionId(), action.versionFenceSha256(),
                action.workspaceId(), canonicalScopes(
                effectiveReadScopes, postCandidatePaths, false),
                canonicalScopes(writeScopes, postCandidatePaths, true),
                base);
    }

    private static List<String> canonicalScopes(
            List<String> requested,
            List<String> effectivePaths,
            boolean writable) {
        if (requested == null || requested.isEmpty()) {
            return requested == null ? List.of() : List.copyOf(requested);
        }
        Map<String, String> foldedEffective = new LinkedHashMap<>();
        for (String path : effectivePaths) {
            String folded = path.toLowerCase(Locale.ROOT);
            require(foldedEffective.putIfAbsent(folded, path) == null,
                    "chain action Project paths conflict after case folding");
        }
        LinkedHashSet<String> foldedRequested = new LinkedHashSet<>();
        List<String> canonical = new java.util.ArrayList<>();
        for (String raw : requested) {
            String path;
            try {
                path = new ProjectRelativePath(raw).value();
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "chain action Project scope is not portable");
            }
            require(path.equals(raw)
                            && foldedRequested.add(
                            path.toLowerCase(Locale.ROOT)),
                    "chain action Project scope conflicts after case folding");
            String exact = foldedEffective.get(path.toLowerCase(Locale.ROOT));
            if (exact != null) {
                require(exact.equals(path),
                        "chain action Project scope conflicts after case folding");
                canonical.add(exact);
                continue;
            }
            if (path.contains("/")) {
                require(writable,
                        "chain action read scope is missing");
                canonical.add(path);
                continue;
            }
            String basename = path.substring(path.lastIndexOf('/') + 1);
            List<String> aliases = effectivePaths.stream()
                    .filter(known -> known.substring(
                            known.lastIndexOf('/') + 1)
                            .equalsIgnoreCase(basename))
                    .toList();
            require(aliases.size() <= 1,
                    "chain action Project scope is ambiguous");
            if (aliases.size() == 1) {
                canonical.add(aliases.get(0));
            } else {
                require(writable,
                        "chain action read scope is missing");
                canonical.add(path);
            }
        }
        return List.copyOf(canonical);
    }

    private ChainActionWorkspaceAuthority.BaseCandidateAuthority baseCandidate(
            TaskRecord task, ChainEffectRuntime.FrozenMutation action) {
        if (ChainIdentity.NONE.equals(action.baseCandidateKey())) {
            return new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                    ChainIdentity.NONE, task.initialProjectVersion(), null,
                    List.of());
        }

        List<WorkspaceCandidateRecord> matches = workflow
                .findWorkspaceCandidates(task.taskId()).stream()
                .filter(value -> value.workspaceId().equals(action.workspaceId())
                        && value.candidateFingerprint().equals(
                        action.baseCandidateKey()))
                .toList();
        require(matches.size() == 1,
                "base Candidate does not identify one Workspace binding");
        WorkspaceCandidateRecord binding = matches.get(0);
        require(binding.taskId().equals(task.taskId())
                        && binding.workspaceId().equals(action.workspaceId())
                        && binding.baseProjectVersion().equals(
                        task.initialProjectVersion()),
                "base Candidate binding changed the frozen Project");
        List<ActionBindingRecord> sourceActions =
                workflow.findActionBindings(task.taskId()).stream()
                        .filter(value -> value.actionId().equals(
                                binding.actionId()))
                        .toList();
        require(sourceActions.size() == 1
                        && sourceActions.get(0).taskId().equals(task.taskId())
                        && sourceActions.get(0).workspaceId().equals(
                        binding.workspaceId())
                        && sourceActions.get(0).versionFenceSha256().equals(
                        binding.versionFenceSha256()),
                "base Candidate action binding changed");

        CandidateArtifactResponse candidate = candidates.getCurrent(
                task.userId(), binding.artifactId());
        require(candidate.artifactId().equals(binding.artifactId())
                        && candidate.projectId() == task.projectId()
                        && candidate.projectVersion().value().equals(
                        binding.baseProjectVersion())
                        && candidate.governanceStatus()
                        == CandidateChangeSet.GovernanceStatus.VALIDATED
                        && candidate.fingerprint().sha256().equals(
                        binding.candidateFingerprint())
                        && candidate.fingerprint().sha256().equals(
                        action.baseCandidateKey())
                        && diffDigest(candidate).equals(binding.diffDigest()),
                "base Candidate artifact does not match its chain binding");
        require(candidate.reviewDiff().format().equals(
                        CandidateReviewDiff.FORMAT)
                        && candidate.reviewDiff().projectVersion().equals(
                        candidate.projectVersion())
                        && candidate.reviewDiff().sourceCandidateFingerprint()
                        .equals(candidate.fingerprint()),
                "base Candidate review projection identity changed");
        List<ChainActionWorkspaceAuthority.TypedChange> changes = candidate
                .changes().stream()
                .map(change -> typedChange(change, candidate))
                .sorted(Comparator.comparing(
                        ChainActionWorkspaceAuthority.TypedChange::path))
                .toList();
        List<ChainActionWorkspaceAuthority.TypedChange> projected = candidate
                .reviewDiff().entries().stream()
                .map(ProductChainActionWorkspaceAuthorityFactory::typedChange)
                .sorted(Comparator.comparing(
                        ChainActionWorkspaceAuthority.TypedChange::path))
                .toList();
        require(!changes.isEmpty() && projected.equals(changes),
                "base Candidate content and diff projection disagree");
        return new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                action.baseCandidateKey(), task.initialProjectVersion(),
                binding.artifactId(), changes);
    }

    private static ChainActionWorkspaceAuthority.TypedChange typedChange(
            CandidateFileChange change,
            CandidateArtifactResponse candidate) {
        require(change != null
                        && change.projectVersion().equals(
                        candidate.projectVersion()),
                "base Candidate change version changed");
        return new ChainActionWorkspaceAuthority.TypedChange(
                ChainActionWorkspaceAuthority.ChangeType.valueOf(
                        change.type().name()),
                change.relativePath().value(),
                change.baseFileHash() == null ? null
                        : change.baseFileHash().sha256(),
                change.resultFileHash() == null ? null
                        : change.resultFileHash().sha256(),
                change.candidateText() == null ? null
                        : change.candidateText().text());
    }

    private static ChainActionWorkspaceAuthority.TypedChange typedChange(
            CandidateReviewDiff.Entry entry) {
        return new ChainActionWorkspaceAuthority.TypedChange(
                ChainActionWorkspaceAuthority.ChangeType.valueOf(
                        entry.type().name()),
                entry.relativePath().value(),
                entry.baseFileHash() == null ? null
                        : entry.baseFileHash().sha256(),
                entry.resultFileHash() == null ? null
                        : entry.resultFileHash().sha256(),
                entry.replacementText());
    }

    private static List<String> postCandidatePaths(
            List<ProjectFileEntry> manifest,
            List<ChainActionWorkspaceAuthority.TypedChange> changes) {
        Map<String, ProjectFileEntry> files = new LinkedHashMap<>();
        Map<String, String> folded = new LinkedHashMap<>();
        for (ProjectFileEntry file : manifest) {
            String path = new ProjectRelativePath(file.path()).value();
            require(path.equals(file.path())
                            && files.putIfAbsent(path, file) == null
                            && folded.putIfAbsent(
                            path.toLowerCase(Locale.ROOT), path) == null,
                    "chain action Project manifest paths conflict");
        }
        for (ChainActionWorkspaceAuthority.TypedChange change : changes) {
            String path = change.path();
            String foldedPath = path.toLowerCase(Locale.ROOT);
            String existingPath = folded.get(foldedPath);
            if (change.type()
                    == ChainActionWorkspaceAuthority.ChangeType.ADD) {
                require(existingPath == null,
                        "base Candidate ADD path already exists");
                folded.put(foldedPath, path);
                files.put(path, new ProjectFileEntry(
                        path, change.text().getBytes(
                        StandardCharsets.UTF_8).length,
                        java.time.Instant.EPOCH,
                        change.resultSha256()));
            } else {
                require(path.equals(existingPath),
                        "base Candidate source path is missing or changed case");
                ProjectFileEntry original = files.get(path);
                require(original.sha256().equals(change.baseSha256()),
                        "base Candidate source hash drifted");
                if (change.type()
                        == ChainActionWorkspaceAuthority.ChangeType.DELETE) {
                    files.remove(path);
                    folded.remove(foldedPath);
                }
            }
        }
        return files.keySet().stream().sorted().toList();
    }

    private static String diffDigest(CandidateArtifactResponse candidate) {
        StringBuilder canonical = new StringBuilder(
                candidate.projectVersion().value());
        candidate.reviewDiff().entries().stream()
                .sorted(Comparator.comparing(value ->
                        value.relativePath().value()))
                .forEach(value -> canonical.append('\0').append(value.type())
                        .append('\0').append(value.relativePath().value())
                        .append('\0').append(value.baseFileHash() == null
                                ? ChainIdentity.NONE
                                : value.baseFileHash().sha256())
                        .append('\0').append(value.resultFileHash() == null
                                ? ChainIdentity.NONE
                                : value.resultFileHash().sha256()));
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
