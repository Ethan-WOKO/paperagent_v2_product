package com.yanban.api.agent.v2.chain.effect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.EvidenceLedger;
import com.yanban.api.agent.EvidenceRef;
import com.yanban.api.agent.EvidenceSourceType;
import com.yanban.api.agent.EvidenceVersionStatus;
import com.yanban.api.agent.ProjectRuntimeContext;
import com.yanban.api.agent.ProjectMaterialScope;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Materializes both formal Workspace-change paths into the retained Candidate authority. */
@Component
public final class ProductChainWorkspaceCandidateAuthority
        implements ChainEffectRuntime.WorkspaceCandidateAuthority {
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainModelRepository models;
    private final EffectOutcomeRepository outcomes;
    private final CandidateChangeArtifactService candidates;
    private final ProjectService projects;
    private final ProductChainTaskMutationFence mutationFence;
    private final ObjectMapper json;
    private final ProductChainCandidateMaterializationFailureRepositoryAdapter
            failures;
    private final long maxReplacementBytes;

    public ProductChainWorkspaceCandidateAuthority(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainModelRepository models,
            EffectOutcomeRepository outcomes,
            CandidateChangeArtifactService candidates,
            ProjectService projects,
            ProductChainTaskMutationFence mutationFence,
            ObjectMapper json,
            ProductChainCandidateMaterializationFailureRepositoryAdapter
                    failures,
            ProjectStorageProperties storage) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.models = Objects.requireNonNull(models, "models");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.mutationFence = Objects.requireNonNull(mutationFence, "mutationFence");
        this.json = Objects.requireNonNull(json, "json");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.maxReplacementBytes = Objects.requireNonNull(storage, "storage")
                .getMaxFileBytes();
        if (maxReplacementBytes < 1) {
            throw new IllegalArgumentException(
                    "Project maxFileBytes must be positive");
        }
    }

    @Override
    public Optional<ChainEffectRuntime.MaterializedCandidate> reconcile(
            ChainEffectRuntime.CandidateMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        List<WorkspaceCandidateRecord> matches = workflow
                .findWorkspaceCandidates(mutation.mutation().taskId()).stream()
                .filter(value -> value.actionId().equals(
                        mutation.mutation().actionId()))
                .toList();
        require(matches.size() <= 1,
                "one action has multiple Candidate bindings");
        var failure = failures.findCandidateMaterializationFailure(
                mutation.mutation().taskId(), mutation.mutation().actionId());
        require(matches.isEmpty() || failure.isEmpty(),
                "one action has both Candidate and Candidate failure");
        if (matches.isEmpty() && failure.isPresent()) {
            var value = failure.orElseThrow();
            var frozen = mutation.mutation();
            require(value.taskId().equals(frozen.taskId())
                            && value.actionId().equals(frozen.actionId())
                            && value.workspaceId().equals(frozen.workspaceId())
                            && value.baseCandidateKey().equals(
                            frozen.baseCandidateKey())
                            && value.mutationAuthorityType().equals(
                            mutation.mutationAuthorityType())
                            && value.mutationAuthorityRef().equals(
                            mutation.mutationAuthorityRef())
                            && value.versionFenceSha256().equals(
                            frozen.versionFenceSha256()),
                    "persisted Candidate failure changed immutable identity");
            return Optional.of(failed(frozen, value));
        }
        if (matches.isEmpty()) return Optional.empty();
        WorkspaceCandidateRecord binding = matches.get(0);
        var task = task(mutation.mutation().taskId());
        CandidateArtifactResponse candidate = candidates.getCurrent(
                task.userId(), binding.artifactId());
        require(task.projectId() != null
                        && task.initialProjectVersion() != null
                        && Objects.equals(candidate.projectId(),
                        task.projectId())
                        && binding.baseProjectVersion().equals(
                        task.initialProjectVersion())
                        && candidate.artifactId().equals(binding.artifactId())
                        && candidate.projectVersion().value().equals(
                        binding.baseProjectVersion())
                        && candidate.fingerprint().sha256().equals(
                        binding.candidateFingerprint())
                        && diffDigest(candidate).equals(binding.diffDigest())
                        && binding.workspaceId().equals(
                        mutation.mutation().workspaceId())
                        && binding.versionFenceSha256().equals(
                        mutation.mutation().versionFenceSha256()),
                "persisted Candidate no longer matches its chain binding");
        return Optional.of(materialized(mutation.mutation(), candidate));
    }

    @Override
    public ChainEffectRuntime.MaterializedCandidate materialize(
            ChainEffectRuntime.CandidateMutation mutation,
            ChainEffectRuntime.CandidateBindingPort binding) {
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(binding, "binding");
        Optional<ChainEffectRuntime.MaterializedCandidate> replay =
                reconcile(mutation);
        if (replay.isPresent()) return replay.orElseThrow();

        return mutationFence.materializeCurrent(
                mutation.mutation(), () -> {
                    try {
                        return materializeCurrent(mutation, binding);
                    } catch (CandidateRejection rejected) {
                        return recordFailure(mutation, rejected.code());
                    }
                });
    }

    private ChainEffectRuntime.MaterializedCandidate materializeCurrent(
            ChainEffectRuntime.CandidateMutation mutation,
            ChainEffectRuntime.CandidateBindingPort binding) {
        Optional<ChainEffectRuntime.MaterializedCandidate> replay =
                reconcile(mutation);
        if (replay.isPresent()) return replay.orElseThrow();

        ChainEffectRuntime.FrozenMutation frozen = mutation.mutation();
        var task = task(frozen.taskId());
        require(task.projectId() != null && task.initialProjectVersion() != null,
                "Candidate mutation requires a frozen Project");
        var manifest = projects.manifest(task.userId(), task.projectId());
        require(task.initialProjectVersion().equals(manifest.version()),
                "ProjectVersion changed before Candidate materialization");

        CandidateArtifactResponse base = baseCandidate(frozen, task);
        EffectiveProject view = effectiveProject(
                task.userId(), task.projectId(), manifest, base);
        RequestedBundle requested = requestedChanges(mutation, view);
        if (frozen.sourceKind() == ChainEffectRuntime.SourceKind.TOOL_ACTION) {
            Map<String, String> currentText = new LinkedHashMap<>();
            Map<String, String> replacements = new LinkedHashMap<>();
            for (RequestedChange change : requested.changes()) {
                currentText.put(change.path(), view.required(change.path()).text());
                replacements.put(change.path(), change.text());
            }
            verifyExecutedMutation(mutation, currentText, replacements);
        }

        boolean progressed = applyRequested(view, requested.changes());
        if (!progressed) {
            throw reject(CandidateFailureCode.CANDIDATE_NO_ACTUAL_CHANGE);
        }
        Map<String, CandidateIntent.FileIntent> cumulative = cumulativeChanges(
                view, task.projectId(), frozen.taskId());
        if (cumulative.isEmpty()) {
            throw reject(CandidateFailureCode.CANDIDATE_NO_ACTUAL_CHANGE);
        }

        EvidenceLedger evidence = evidence(
                task.userId(), task.projectId(), task.initialProjectVersion(),
                frozen.taskId(), evidenceSources(view, cumulative.keySet()));
        CandidateArtifactResponse candidate = candidates.store(
                task.userId(), task.sessionId(),
                new ProjectRuntimeContext(
                        task.userId(), task.projectId(),
                        task.initialProjectVersion()),
                new CandidateIntent(
                        task.projectId(),
                        new ProjectVersionRef(task.initialProjectVersion()),
                        List.copyOf(cumulative.values())),
                evidence);
        require(candidate.projectId() == task.projectId()
                        && candidate.projectVersion().value().equals(
                        task.initialProjectVersion())
                        && (base == null || !candidate.fingerprint().sha256()
                        .equals(base.fingerprint().sha256())),
                "Candidate authority returned another Project or no progress");
        ChainEffectRuntime.MaterializedCandidate materialized =
                materialized(frozen, candidate);
        WorkspaceCandidateRecord bound = binding.bind(materialized);
        require(bound.taskId().equals(frozen.taskId())
                        && bound.actionId().equals(frozen.actionId())
                        && bound.workspaceId().equals(frozen.workspaceId())
                        && bound.baseProjectVersion().equals(
                        materialized.baseProjectVersion())
                        && bound.artifactId() == materialized.artifactId()
                        && bound.candidateFingerprint().equals(
                        materialized.candidateFingerprint())
                        && bound.diffDigest().equals(
                        materialized.diffDigest())
                        && bound.versionFenceSha256().equals(
                        frozen.versionFenceSha256()),
                "Candidate binding callback returned another authority");
        return materialized;
    }

    private CandidateArtifactResponse baseCandidate(
            ChainEffectRuntime.FrozenMutation mutation,
            ChainPersistenceRecords.TaskRecord task) {
        if (ChainIdentity.NONE.equals(mutation.baseCandidateKey())) return null;
        List<WorkspaceCandidateRecord> matches = workflow
                .findWorkspaceCandidates(mutation.taskId()).stream()
                .filter(value -> value.candidateFingerprint().equals(
                        mutation.baseCandidateKey()))
                .toList();
        require(matches.size() == 1,
                "base Candidate does not identify one chain binding");
        WorkspaceCandidateRecord binding = matches.get(0);
        require(binding.taskId().equals(mutation.taskId())
                        && binding.workspaceId().equals(mutation.workspaceId())
                        && binding.baseProjectVersion().equals(
                        task.initialProjectVersion()),
                "base Candidate binding changed immutable scope");
        List<ChainPersistenceRecords.ActionBindingRecord> actions = workflow
                .findActionBindings(mutation.taskId()).stream()
                .filter(value -> value.actionId().equals(binding.actionId()))
                .toList();
        require(actions.size() == 1
                        && actions.get(0).workspaceId().equals(
                        binding.workspaceId())
                        && actions.get(0).versionFenceSha256().equals(
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
                        mutation.baseCandidateKey())
                        && candidate.fingerprint().sha256().equals(
                        binding.candidateFingerprint())
                        && diffDigest(candidate).equals(binding.diffDigest()),
                "base Candidate artifact changed");
        verifyCandidateProjection(candidate);
        return candidate;
    }

    private RequestedBundle requestedChanges(
            ChainEffectRuntime.CandidateMutation mutation,
            EffectiveProject view) {
        ChainEffectRuntime.FrozenMutation frozen = mutation.mutation();
        ModelProposalRecord proposal = models.findProposal(frozen.proposalId())
                .orElseThrow(() -> failure("Candidate proposal is unavailable"));
        require(proposal.taskId().equals(frozen.taskId()),
                "Candidate proposal belongs to another task");
        JsonNode proposalPayload;
        try {
            proposalPayload = json.readTree(proposal.payload().json());
        } catch (java.io.IOException invalid) {
            throw failure("Candidate proposal payload authority is invalid JSON");
        }
        if (frozen.sourceKind() == ChainEffectRuntime.SourceKind.TOOL_ACTION) {
            require(proposal.proposalKind()
                            == ChainProposalKind.EXECUTOR_TOOL_ACTION
                            && "TOOL_EFFECT_RESULT".equals(
                            mutation.mutationAuthorityType()),
                    "tool Candidate authority is invalid");
            JsonNode arguments;
            try {
                arguments = json.readTree(
                        proposalPayload.path("completeArguments").asText());
            } catch (java.io.IOException invalid) {
                throw failure("tool action authority arguments are invalid JSON");
            }
            require("project.candidate.compose".equals(
                            proposalPayload.path("toolId").asText())
                            && arguments.isObject()
                            && "compose".equals(
                            arguments.path("operation").asText()),
                    "tool effect cannot produce a Candidate");
            Map<String, String> replacements = strictReplacements(
                    arguments.path("paths"),
                    arguments.path("replacements"));
            List<RequestedChange> changes = new ArrayList<>();
            for (var entry : replacements.entrySet()) {
                String path = resolveExistingPath(entry.getKey(), view);
                changes.add(new RequestedChange(
                        CandidateIntent.Type.MODIFY, path,
                        view.required(path).effectiveHash,
                        entry.getValue(), true));
            }
            return new RequestedBundle(List.copyOf(changes));
        }
        require(proposal.proposalKind()
                        == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE
                        && "WORKSPACE_CHANGE_BODY".equals(
                        mutation.mutationAuthorityType())
                        && Objects.equals(proposal.bodyAuthorityRef(),
                        mutation.mutationAuthorityRef()),
                "Workspace-change Candidate authority is invalid");
        ContentRecord body = models.findContent(
                        mutation.mutationAuthorityRef())
                .orElseThrow(() -> failure(
                        "Workspace change body is unavailable"));
        require(body.taskId().equals(frozen.taskId())
                        && body.contentId().equals(
                        mutation.mutationAuthorityRef())
                        && body.invocationId().equals(
                        proposal.invocationId())
                        && body.contentKind()
                        == ChainContentKind.WORKSPACE_CHANGE_BODY
                        && body.bodySha256().equals(sha256(body.body())),
                "Workspace change body belongs to another proposal");
        JsonNode bundle;
        try {
            bundle = json.readTree(body.body());
        } catch (java.io.IOException invalid) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
        }
        JsonNode baseCandidateRef = proposalPayload.path(
                "baseCandidateRef");
        JsonNode manifestChanges = proposalPayload.path(
                "manifestChanges");
        require(baseCandidateRef.isTextual()
                        && frozen.baseCandidateKey().equals(
                        baseCandidateRef.textValue()),
                "Workspace change proposal uses a stale base Candidate");
        require(manifestChanges.isArray()
                        && manifestChanges.isEmpty(),
                "manifest changes are not supported by this Candidate authority");
        List<String> targets = strictTargets(
                proposalPayload.path("targetFiles"));
        if (bundle.isObject() && bundle.size() == 1
                && bundle.path("changes").isArray()) {
            return strictChanges(targets, bundle.path("changes"), view);
        }
        if (!bundle.isObject() || bundle.size() != 1
                || !bundle.path("replacements").isArray()) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
        }
        Map<String, String> legacy = strictReplacements(
                proposalPayload.path("targetFiles"),
                bundle.path("replacements"));
        List<RequestedChange> changes = new ArrayList<>();
        for (var entry : legacy.entrySet()) {
            String path = resolveExistingPath(entry.getKey(), view);
            changes.add(new RequestedChange(
                    CandidateIntent.Type.MODIFY, path,
                    view.required(path).effectiveHash,
                    entry.getValue(), true));
        }
        return new RequestedBundle(List.copyOf(changes));
    }

    private RequestedBundle strictChanges(
            List<String> targets, JsonNode changes, EffectiveProject view) {
        if (changes.isEmpty() || changes.size() != targets.size()) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
        }
        List<RequestedChange> result = new ArrayList<>();
        LinkedHashSet<String> rawPaths = new LinkedHashSet<>();
        LinkedHashSet<String> canonicalPaths = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode item : changes) {
            if (!item.isObject()
                    || !item.path("type").isTextual()
                    || !item.path("path").isTextual()
                    || !item.path("expectedBaselineSha256").isTextual()) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            CandidateIntent.Type type;
            try {
                type = CandidateIntent.Type.valueOf(
                        item.path("type").textValue());
            } catch (RuntimeException invalid) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            boolean hasText = item.has("text");
            if ((type == CandidateIntent.Type.DELETE
                    && (item.size() != 3 || hasText))
                    || (type != CandidateIntent.Type.DELETE
                    && (item.size() != 4 || !hasText
                    || !item.path("text").isTextual()))) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            String rawPath = canonicalInputPath(
                    item.path("path").textValue());
            if (!targets.get(index++).equals(rawPath)
                    || !rawPaths.add(conflictKey(rawPath))) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            String baseline = item.path(
                    "expectedBaselineSha256").textValue();
            String path;
            if (type == CandidateIntent.Type.ADD) {
                if (!ChainIdentity.NONE.equals(baseline)) {
                    throw reject(CandidateFailureCode
                            .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
                }
                path = resolveAddPath(rawPath, view);
            } else {
                if (!baseline.matches("[a-f0-9]{64}")) {
                    throw reject(CandidateFailureCode
                            .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
                }
                path = resolveExistingPath(rawPath, view);
                if (!baseline.equals(view.required(path).effectiveHash)) {
                    throw reject(CandidateFailureCode
                            .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
                }
            }
            String text = hasText ? item.path("text").textValue() : null;
            if (!canonicalPaths.add(conflictKey(path))) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS);
            }
            result.add(new RequestedChange(
                    type, path, baseline, text, hasText));
        }
        return new RequestedBundle(List.copyOf(result));
    }

    private List<String> strictTargets(JsonNode targetFiles) {
        if (!targetFiles.isArray() || targetFiles.isEmpty()) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
        }
        List<String> result = new ArrayList<>();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (JsonNode target : targetFiles) {
            if (!target.isTextual()) throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            String path = canonicalInputPath(target.textValue());
            if (!distinct.add(conflictKey(path))) throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            result.add(path);
        }
        return List.copyOf(result);
    }

    private Map<String, String> strictReplacements(
            JsonNode targetFiles, JsonNode replacements) {
        if (!targetFiles.isArray() || targetFiles.isEmpty()
                || !replacements.isArray()
                || replacements.size() != targetFiles.size()) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
        }
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        for (JsonNode target : targetFiles) {
            if (!target.isTextual()) throw reject(CandidateFailureCode
                    .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            expected.add(candidatePath(target.textValue()));
        }
        if (expected.size() != targetFiles.size()) throw reject(
                CandidateFailureCode.CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode replacement : replacements) {
            if (!replacement.isObject() || replacement.size() != 2
                    || !replacement.path("path").isTextual()
                    || !replacement.path("text").isTextual()) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            String path = candidatePath(
                    replacement.path("path").textValue());
            String text = replacement.path("text").textValue();
            if (!expected.contains(path)
                    || result.putIfAbsent(path, text) != null) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
        }
        if (!result.keySet().equals(expected)) throw reject(
                CandidateFailureCode.CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
        return result;
    }

    private static String candidatePath(String value) {
        try {
            return new ProjectRelativePath(value).value();
        } catch (IllegalArgumentException invalid) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS);
        }
    }

    private static String canonicalInputPath(String value) {
        String path = candidatePath(value);
        if (!path.equals(value)) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS);
        }
        return path;
    }

    private String resolveExistingPath(
            String requested, EffectiveProject view) {
        List<String> existing = view.existingPaths();
        ProjectMaterialScope.CanonicalPathResolution resolution =
                ProjectMaterialScope.resolveCanonicalPaths(
                        Set.of(requested), existing);
        if (!resolution.valid()) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS);
        }
        String path = resolution.canonicalAlias(requested);
        if (path == null || !view.required(path).effectiveExists) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS);
        }
        return path;
    }

    private String resolveAddPath(String requested, EffectiveProject view) {
        String path = canonicalInputPath(requested);
        FileState exact = view.states.get(path);
        if (exact != null && exact.effectiveExists) {
            throw reject(CandidateFailureCode
                    .CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS);
        }
        String key = conflictKey(path);
        for (FileState state : view.states.values()) {
            if (conflictKey(state.path).equals(key)
                    && !state.path.equals(path)) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS);
            }
        }
        return path;
    }

    private EffectiveProject effectiveProject(
            long userId, long projectId,
            com.yanban.api.project.ProjectManifestResponse manifest,
            CandidateArtifactResponse base) {
        EffectiveProject view = new EffectiveProject(
                userId, projectId, projects);
        if (manifest.files() != null) {
            for (var file : manifest.files().stream()
                    .sorted(Comparator.comparing(value -> value.path()))
                    .toList()) {
                String path = canonicalInputPath(file.path());
                view.addOriginal(path, file.sha256());
            }
        }
        if (base == null) return view;
        for (CandidateReviewDiff.Entry entry : base.reviewDiff().entries()) {
            String path = canonicalInputPath(entry.relativePath().value());
            FileState state = view.states.get(path);
            switch (entry.type()) {
                case ADD -> {
                    require(state == null || !state.originalExists,
                            "base Candidate ADD conflicts with Project");
                    if (state == null) {
                        state = new FileState(path, false, null);
                        view.put(state);
                    }
                    state.setEffective(entry.replacementText(),
                            requiredHash(entry.resultFileHash(),
                                    "base Candidate ADD result hash"));
                }
                case MODIFY -> {
                    require(state != null && state.originalExists
                                    && entry.baseFileHash() != null
                                    && entry.baseFileHash().sha256().equals(
                                    state.originalHash),
                            "base Candidate MODIFY baseline changed");
                    state.setEffective(entry.replacementText(),
                            requiredHash(entry.resultFileHash(),
                                    "base Candidate MODIFY result hash"));
                }
                case DELETE -> {
                    require(state != null && state.originalExists
                                    && entry.baseFileHash() != null
                                    && entry.baseFileHash().sha256().equals(
                                    state.originalHash),
                            "base Candidate DELETE baseline changed");
                    state.deleteEffective();
                }
            }
        }
        return view;
    }

    private static void verifyCandidateProjection(
            CandidateArtifactResponse candidate) {
        require(candidate.changes() != null
                        && candidate.reviewDiff() != null
                        && candidate.reviewDiff().sourceCandidateFingerprint()
                        .equals(candidate.fingerprint())
                        && candidate.reviewDiff().projectVersion().equals(
                        candidate.projectVersion())
                        && candidate.changes().size()
                        == candidate.reviewDiff().entries().size(),
                "base Candidate projection is incomplete");
        Map<String, CandidateFileChange> changes = new LinkedHashMap<>();
        for (CandidateFileChange change : candidate.changes()) {
            String path = canonicalInputPath(
                    change.relativePath().value());
            require(change.projectVersion().equals(
                            candidate.projectVersion())
                            && changes.putIfAbsent(path, change) == null,
                    "base Candidate changes are invalid");
        }
        for (CandidateReviewDiff.Entry entry
                : candidate.reviewDiff().entries()) {
            CandidateFileChange change = changes.remove(
                    entry.relativePath().value());
            require(change != null
                            && change.type() == entry.type()
                            && Objects.equals(change.baseFileHash(),
                            entry.baseFileHash())
                            && Objects.equals(change.resultFileHash(),
                            entry.resultFileHash())
                            && Objects.equals(
                            change.candidateText() == null ? null
                                    : change.candidateText().text(),
                            entry.replacementText()),
                    "base Candidate change and review projection disagree");
        }
        require(changes.isEmpty(),
                "base Candidate review projection omitted changes");
    }

    private static String requiredHash(
            FileHash hash, String authority) {
        require(hash != null, authority + " is unavailable");
        return hash.sha256();
    }

    private boolean applyRequested(
            EffectiveProject view, List<RequestedChange> changes) {
        boolean progressed = false;
        for (RequestedChange change : changes) {
            if (change.type() != CandidateIntent.Type.DELETE) {
                byte[] bytes = change.text().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > maxReplacementBytes) {
                    throw reject(CandidateFailureCode
                            .CANDIDATE_REPLACEMENT_TOO_LARGE);
                }
            }
            FileState state = view.states.get(change.path());
            switch (change.type()) {
                case ADD -> {
                    if (state == null) {
                        state = new FileState(change.path(), false, null);
                        view.put(state);
                    }
                    require(!state.effectiveExists,
                            "ADD target became present during materialization");
                    state.setEffective(change.text(), sha256(change.text()));
                    progressed = true;
                }
                case MODIFY -> {
                    require(state != null && state.effectiveExists,
                            "MODIFY target became absent during materialization");
                    String resultHash = sha256(change.text());
                    if (!resultHash.equals(state.effectiveHash)) {
                        state.setEffective(change.text(), resultHash);
                        progressed = true;
                    }
                }
                case DELETE -> {
                    require(state != null && state.effectiveExists,
                            "DELETE target became absent during materialization");
                    state.deleteEffective();
                    progressed = true;
                }
            }
        }
        return progressed;
    }

    private Map<String, CandidateIntent.FileIntent> cumulativeChanges(
            EffectiveProject view, long projectId, String taskId) {
        Map<String, CandidateIntent.FileIntent> result = new LinkedHashMap<>();
        for (FileState state : view.states.values().stream()
                .sorted(Comparator.comparing(value -> value.path))
                .toList()) {
            CandidateIntent.Type type;
            FileHash baseline;
            String text;
            if (!state.originalExists && state.effectiveExists) {
                type = CandidateIntent.Type.ADD;
                baseline = null;
                text = state.text();
            } else if (state.originalExists && !state.effectiveExists) {
                type = CandidateIntent.Type.DELETE;
                baseline = new FileHash(state.originalHash);
                text = null;
            } else if (state.originalExists && state.effectiveExists
                    && !state.originalHash.equals(state.effectiveHash)) {
                type = CandidateIntent.Type.MODIFY;
                baseline = new FileHash(state.originalHash);
                text = state.text();
            } else {
                continue;
            }
            result.put(state.path, new CandidateIntent.FileIntent(
                    type, new ProjectRelativePath(state.path), baseline, text,
                    List.of(evidenceId(projectId, taskId, state.path))));
        }
        return result;
    }

    private Map<String, String> evidenceSources(
            EffectiveProject view, Set<String> targetPaths) {
        String fallback = view.states.values().stream()
                .filter(value -> value.originalExists)
                .map(value -> value.path)
                .sorted().findFirst().orElse(null);
        Map<String, String> result = new LinkedHashMap<>();
        for (String target : targetPaths) {
            FileState state = view.states.get(target);
            String source = state != null && state.originalExists
                    ? target : fallback;
            if (source == null) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            result.put(target, source);
        }
        return result;
    }

    private static String conflictKey(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private void verifyExecutedMutation(
            ChainEffectRuntime.CandidateMutation mutation,
            Map<String, String> currentText,
            Map<String, String> replacements) {
        if (mutation.mutation().sourceKind()
                != ChainEffectRuntime.SourceKind.TOOL_ACTION) return;
        PersistedEffectResult result = required(
                outcomes.findResult(new ToolCallId(
                        mutation.mutation().actionId())), "effect receipt");
        require(result.receipt().status() == ReceiptStatus.SUCCESS
                        && result.receipt().id().value().equals(
                        mutation.mutationAuthorityRef()),
                "Candidate is not bound to the successful effect receipt");
        String output = result.receipt().standardOutput().inlineText()
                .orElseThrow(() -> failure(
                        "Candidate effect receipt has no inline result"));
        try {
            JsonNode receipt = json.readTree(output);
            String expected = executionDiffDigest(
                    task(mutation.mutation().taskId()).initialProjectVersion(),
                    currentText, replacements);
            require(receipt.isObject()
                            && expected.equals(
                            receipt.path("diffFingerprint").asText()),
                    "Candidate contents differ from the executed Workspace diff");
        } catch (java.io.IOException invalid) {
            throw failure("Candidate effect receipt is invalid JSON");
        }
    }

    private EvidenceLedger evidence(
            long userId, long projectId, String projectVersion,
            String taskId, Map<String, String> targetSources) {
        List<EvidenceRef> values = new ArrayList<>();
        for (var entry : targetSources.entrySet()) {
            String target = entry.getKey();
            String path = entry.getValue();
            ProjectFileResponse original = projects.readFile(
                    userId, projectId, path);
            int lines = original.content().split("\\R", -1).length;
            values.add(new EvidenceRef(
                    evidenceId(projectId, taskId, target),
                    EvidenceSourceType.PROJECT, "PROJECT", path,
                    "whole-file", null, original.sha256(),
                    "Frozen chain Candidate source", projectVersion,
                    original.sha256(), 1, lines,
                    "agent-chain-candidate-1",
                    EvidenceVersionStatus.VERIFIED));
        }
        return new EvidenceLedger(values);
    }

    private static String evidenceId(
            long projectId, String taskId, String path) {
        return "trusted-plan:" + projectId + ":chain:"
                + sha256(taskId + "\0" + path);
    }

    private static ChainEffectRuntime.MaterializedCandidate materialized(
            ChainEffectRuntime.FrozenMutation mutation,
            CandidateArtifactResponse candidate) {
        return new ChainEffectRuntime.MaterializedCandidate(
                ChainEffectRuntime.CandidateDisposition.COMMITTED,
                mutation.actionId(), mutation.workspaceId(),
                mutation.baseCandidateKey(),
                candidate.projectVersion().value(), candidate.artifactId(),
                candidate.fingerprint().sha256(), diffDigest(candidate),
                mutation.versionFenceSha256());
    }

    private ChainEffectRuntime.MaterializedCandidate recordFailure(
            ChainEffectRuntime.CandidateMutation mutation,
            CandidateFailureCode code) {
        var frozen = mutation.mutation();
        String id = "candidate-failure." + sha256(
                frozen.taskId() + "\0" + frozen.actionId());
        List<ChainPersistenceRecords.ActionBindingRecord> actions = workflow
                .findActionBindings(frozen.taskId()).stream()
                .filter(value -> value.actionId().equals(frozen.actionId()))
                .toList();
        require(actions.size() == 1,
                "Candidate failure action authority is not unique");
        Instant createdAt = actions.get(0).createdAt();
        var fact = new ChainPersistenceRecords
                .CandidateMaterializationFailureRecord(
                id, frozen.taskId(), "candidate-failure.event." + sha256(id),
                frozen.actionId(), frozen.workspaceId(),
                frozen.baseCandidateKey(), mutation.mutationAuthorityType(),
                mutation.mutationAuthorityRef(), frozen.versionFenceSha256(),
                code.name(), createdAt);
        var event = new ChainPersistenceRecords.AuthorityEventRequest(
                fact.eventId(), fact.taskId(),
                "CANDIDATE_MATERIALIZATION_FAILURE", null,
                sha256(frozen.actionId() + "\0" + code.name()), createdAt);
        var stored = failures.appendCandidateMaterializationFailure(
                new ChainPersistenceRecords.AuthoritativeFact<>(event, fact));
        require(sameFailureIdentity(stored.fact(), fact)
                        && stored.event().eventId().equals(event.eventId())
                        && stored.event().taskId().equals(event.taskId())
                        && stored.event().eventType().equals(event.eventType())
                        && Objects.equals(stored.event().transitionId(),
                        event.transitionId())
                        && stored.event().sourceIdentitySha256().equals(
                        event.sourceIdentitySha256())
                        && stored.fact().createdAt().equals(
                        stored.event().committedAt()),
                "Candidate failure replay changed immutable fields");
        return failed(frozen, stored.fact());
    }

    private static ChainEffectRuntime.MaterializedCandidate failed(
            ChainEffectRuntime.FrozenMutation frozen,
            ChainPersistenceRecords.CandidateMaterializationFailureRecord value) {
        return new ChainEffectRuntime.MaterializedCandidate(
                ChainEffectRuntime.CandidateDisposition.FAILED,
                frozen.actionId(), frozen.workspaceId(),
                frozen.baseCandidateKey(), null, 0, null, null,
                frozen.versionFenceSha256(), value.candidateFailureId(),
                value.errorCode());
    }

    private static boolean sameFailureIdentity(
            ChainPersistenceRecords.CandidateMaterializationFailureRecord left,
            ChainPersistenceRecords.CandidateMaterializationFailureRecord right) {
        return left.candidateFailureId().equals(right.candidateFailureId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.actionId().equals(right.actionId())
                && left.workspaceId().equals(right.workspaceId())
                && left.baseCandidateKey().equals(right.baseCandidateKey())
                && left.mutationAuthorityType().equals(
                right.mutationAuthorityType())
                && left.mutationAuthorityRef().equals(
                right.mutationAuthorityRef())
                && left.versionFenceSha256().equals(
                right.versionFenceSha256())
                && left.errorCode().equals(right.errorCode());
    }

    private static CandidateRejection reject(CandidateFailureCode code) {
        return new CandidateRejection(code);
    }

    private record RequestedBundle(List<RequestedChange> changes) {
        private RequestedBundle {
            changes = List.copyOf(changes);
            if (changes.isEmpty()) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
        }
    }

    private record RequestedChange(
            CandidateIntent.Type type,
            String path,
            String expectedBaselineSha256,
            String text,
            boolean textPresent) {
        private RequestedChange {
            Objects.requireNonNull(type, "type");
            canonicalInputPath(path);
            if (type == CandidateIntent.Type.ADD
                    && !ChainIdentity.NONE.equals(
                    expectedBaselineSha256)) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            if (type != CandidateIntent.Type.ADD
                    && (expectedBaselineSha256 == null
                    || !expectedBaselineSha256.matches("[a-f0-9]{64}"))) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
            if ((type == CandidateIntent.Type.DELETE
                    && (textPresent || text != null))
                    || (type != CandidateIntent.Type.DELETE
                    && (!textPresent || text == null))) {
                throw reject(CandidateFailureCode
                        .CANDIDATE_REPLACEMENT_BUNDLE_INVALID);
            }
        }
    }

    private static final class FileState {
        private final String path;
        private final boolean originalExists;
        private final String originalHash;
        private boolean effectiveExists;
        private String effectiveHash;
        private String effectiveText;

        private FileState(
                String path, boolean originalExists, String originalHash) {
            this.path = canonicalInputPath(path);
            this.originalExists = originalExists;
            this.originalHash = originalHash;
            this.effectiveExists = originalExists;
            this.effectiveHash = originalHash;
        }

        private void setEffective(String text, String hash) {
            require(text != null && hash != null
                            && hash.equals(sha256(text)),
                    "effective Candidate text and hash disagree");
            effectiveExists = true;
            effectiveHash = hash;
            effectiveText = text;
        }

        private void deleteEffective() {
            effectiveExists = false;
            effectiveHash = null;
            effectiveText = null;
        }

        private String text() {
            require(effectiveExists && effectiveText != null,
                    "effective Candidate text is unavailable");
            return effectiveText;
        }
    }

    private static final class EffectiveProject {
        private final long userId;
        private final long projectId;
        private final ProjectService projects;
        private final Map<String, FileState> states = new LinkedHashMap<>();
        private final Map<String, String> conflictPaths = new LinkedHashMap<>();

        private EffectiveProject(
                long userId, long projectId, ProjectService projects) {
            this.userId = userId;
            this.projectId = projectId;
            this.projects = projects;
        }

        private void addOriginal(String path, String hash) {
            require(hash != null && hash.matches("[a-f0-9]{64}"),
                    "Project manifest hash is invalid");
            put(new FileState(path, true, hash));
        }

        private void put(FileState state) {
            String conflict = conflictKey(state.path);
            String previousConflict = conflictPaths.putIfAbsent(
                    conflict, state.path);
            require(previousConflict == null
                            || previousConflict.equals(state.path),
                    "Project paths conflict by case");
            require(states.putIfAbsent(state.path, state) == null,
                    "Project paths are duplicated");
        }

        private FileState required(String path) {
            FileState state = states.get(path);
            require(state != null, "effective Project path is unavailable");
            if (state.effectiveExists && state.effectiveText == null) {
                ProjectFileResponse file = projects.readFile(
                        userId, projectId, path);
                require(path.equals(file.path())
                                && state.effectiveHash.equals(file.sha256())
                                && sha256(file.content()).equals(file.sha256()),
                        "Project file differs from frozen manifest");
                state.effectiveText = file.content();
            }
            return state;
        }

        private List<String> existingPaths() {
            return states.values().stream()
                    .filter(value -> value.effectiveExists)
                    .map(value -> value.path)
                    .sorted().toList();
        }
    }

    private enum CandidateFailureCode {
        CANDIDATE_REPLACEMENT_BUNDLE_INVALID,
        CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS,
        CANDIDATE_REPLACEMENT_TOO_LARGE,
        CANDIDATE_NO_ACTUAL_CHANGE
    }

    private static final class CandidateRejection extends RuntimeException {
        private final CandidateFailureCode code;
        private CandidateRejection(CandidateFailureCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }
        private CandidateFailureCode code() { return code; }
    }

    private io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord task(
            String taskId) {
        return foundations.findTask(taskId)
                .orElseThrow(() -> failure("chain task is unavailable"));
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

    private static String executionDiffDigest(
            String projectVersion, Map<String, String> originals,
            Map<String, String> replacements) {
        StringBuilder canonical = new StringBuilder(projectVersion);
        replacements.keySet().stream().sorted().forEach(path -> canonical
                .append('\0').append("MODIFY")
                .append('\0').append(path)
                .append('\0').append(sha256(originals.get(path)))
                .append('\0').append(sha256(replacements.get(path))));
        return sha256(canonical.toString());
    }

    private static <T> T required(
            PersistenceResult<T> result, String authority) {
        require(result.successful(), authority + " is unavailable");
        return result.value().orElseThrow(
                () -> failure(authority + " has no value"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw failure(message);
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }
}
