package com.yanban.api.project;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.V2SandboxInputFingerprint;
import com.yanban.api.agent.v2.chain.effect.ProjectCandidateEffectAuthority;
import com.yanban.api.agent.v2.effect.project.NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.OutputCapture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.springframework.stereotype.Service;

/**
 * Applies a natural-language Candidate only when the final successful sandbox
 * Receipt proves that it executed the exact Candidate text.
 */
@Service
public class AgentCandidateAutoApplicationService {
    private static final String CANDIDATE_KIND =
            "project.candidate.compose";
    private static final String SANDBOX_KIND = "sandbox.execute";

    private final NaturalLanguageCandidateAuthorityStore authorities;
    private final CandidateChangeArtifactService candidates;
    private final ProjectService projects;
    private final V2EffectHistorySource effects;
    private final ChainWorkflowRepository chainWorkflow;
    private final ProjectRevisionWorkflowService revisions;

    public AgentCandidateAutoApplicationService(
            NaturalLanguageCandidateAuthorityStore authorities,
            CandidateChangeArtifactService candidates,
            ProjectService projects,
            V2EffectHistorySource effects,
            ChainWorkflowRepository chainWorkflow,
            ProjectRevisionWorkflowService revisions) {
        this.authorities = authorities;
        this.candidates = candidates;
        this.projects = projects;
        this.effects = effects;
        this.chainWorkflow = chainWorkflow;
        this.revisions = revisions;
    }

    public ProjectRevisionOperationResponse apply(
            Long userId, Long turnId, String planId, Long artifactId) {
        var authority = authorities.require(planId);
        if (!userId.equals(authority.userId())
                || !turnId.equals(authority.turnId())) {
            throw failed("candidate_authority");
        }
        VerificationProof proof = proof(authority, planId, artifactId);
        CandidateArtifactResponse candidate = candidates.getCurrent(
                userId, artifactId);
        if (candidate.projectId() != authority.projectId()
                || !candidate.projectVersion().value()
                        .equals(authority.projectVersion())
                || !candidatePaths(candidate).equals(
                        new LinkedHashSet<>(authority.paths()))) {
            throw failed("candidate_binding");
        }

        String key = "agent-auto-apply:" + hash(planId).substring(0, 32);
        return revisions.applyAutomatically(
                userId, authority.projectId(), artifactId, key,
                authority.projectVersion(),
                candidate.fingerprint().sha256(), proof.receiptId());
    }

    /** Returns only after the exact saved files have matching sandbox proof. */
    public VerificationProof proof(String planId, Long artifactId) {
        return proof(authorities.require(planId), planId, null, artifactId);
    }

    /** Returns exact proof scoped to the Step that produced the final Candidate. */
    public VerificationProof proof(
            String planId, String stepId, Long artifactId) {
        return proof(authorities.require(planId), planId, stepId, artifactId);
    }

    /**
     * Returns exact proof for a Candidate owned by the formal chain.
     *
     * <p>The formal chain does not write the legacy natural-language Candidate
     * authority table.  Its authority is the immutable Candidate action and
     * WorkspaceCandidate binding, so callers must supply that exact action and
     * the Step whose sandbox receipt performs the final validation.</p>
     */
    public VerificationProof proofChain(
            Long userId,
            Long projectId,
            String projectVersion,
            String taskId,
            String planId,
            String candidateActionId,
            String candidateWorkspaceId,
            String validationStepId,
            Long artifactId) {
        CandidateArtifactResponse candidate = candidates.getCurrent(
                userId, artifactId);
        if (candidate.projectId() != projectId
                || !candidate.projectVersion().value().equals(projectVersion)) {
            throw failed("candidate_binding");
        }
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> bindings =
                chainWorkflow.findWorkspaceCandidates(taskId).stream()
                        .filter(value -> value.taskId().equals(taskId))
                        .filter(value -> value.actionId().equals(candidateActionId)
                                && value.workspaceId().equals(candidateWorkspaceId)
                                && value.artifactId() == artifactId
                                && value.baseProjectVersion().equals(projectVersion)
                                && value.candidateFingerprint().equals(
                                candidate.fingerprint().sha256()))
                        .toList();
        if (bindings.size() != 1) {
            throw failed("chain_candidate_binding");
        }
        return requireFinalSandbox(
                userId, projectId, planId, validationStepId, candidate,
                new ChainProofScope(taskId, candidateWorkspaceId));
    }

    private VerificationProof proof(
            ProjectCandidateEffectAuthority authority,
            String planId, Long artifactId) {
        return proof(authority, planId, null, artifactId);
    }

    private VerificationProof proof(
            ProjectCandidateEffectAuthority authority,
            String planId, String stepId, Long artifactId) {
        CandidateArtifactResponse candidate = candidates.getCurrent(
                authority.userId(), artifactId);
        if (candidate.projectId() != authority.projectId()
                || !candidate.projectVersion().value()
                        .equals(authority.projectVersion())
                || !candidatePaths(candidate).equals(
                        new LinkedHashSet<>(authority.paths()))) {
            throw failed("candidate_binding");
        }
        return requireFinalSandbox(
                authority.userId(), authority.projectId(),
                planId, stepId, candidate, null);
    }

    private VerificationProof requireFinalSandbox(
            Long userId, Long projectId, String planId,
            String stepId,
            CandidateArtifactResponse candidate,
            ChainProofScope chainProof) {
        boolean formalChainProof = chainProof != null;
        List<V2EffectHistorySource.Entry> history = stepId == null
                || formalChainProof
                ? effects.inspect(new PlanId(planId))
                : effects.inspect(new PlanId(planId), new PlanStepId(stepId));
        int lastCandidate = -1;
        int lastSandbox = -1;
        V2EffectHistorySource.Entry candidateComposition = null;
        V2EffectHistorySource.Entry sandbox = null;
        for (int index = 0; index < history.size(); index++) {
            var entry = history.get(index);
            String kind = entry.intent().intent().kind();
            if (!formalChainProof && CANDIDATE_KIND.equals(kind)
                    && entry.successful()) {
                lastCandidate = index;
                candidateComposition = entry;
            }
            boolean validationStep = stepId == null || stepId.equals(
                    entry.intent().intent().stepId().value());
            if (SANDBOX_KIND.equals(kind) && validationStep) {
                lastSandbox = index;
                sandbox = entry;
            }
        }
        if ((!formalChainProof && (lastCandidate < 0
                || candidateComposition == null
                || lastSandbox <= lastCandidate))
                || sandbox == null || !sandbox.completed()) {
            throw failed("sandbox_order");
        }
        if (formalChainProof) {
            String sandboxActionId = sandbox.intent().intent()
                    .toolCallId().value();
            List<ChainPersistenceRecords.ActionBindingRecord> actions =
                    chainWorkflow.findActionBindings(chainProof.taskId()).stream()
                            .filter(value -> value.actionId().equals(
                            sandboxActionId))
                            .toList();
            if (actions.size() != 1) {
                throw failed("chain_sandbox_binding");
            }
            ChainPersistenceRecords.ActionBindingRecord action = actions.get(0);
            if (!action.taskId().equals(chainProof.taskId())
                    || !action.planId().equals(planId)
                    || !action.stepId().equals(stepId)
                    || !action.workspaceId().equals(chainProof.workspaceId())
                    || !action.baseCandidateKey().equals(
                    candidate.fingerprint().sha256())) {
                throw failed("chain_sandbox_binding");
            }
        }
        var receipt = sandbox.result().receipt();
        if (receipt.status() != ReceiptStatus.SUCCESS
                || receipt.exitCode().orElse(-1) != 0) {
            throw failed("sandbox_result");
        }

        List<String> paths = sandboxPaths(sandbox);
        Map<String, CandidateFileChange> changes = candidateChanges(candidate);
        if (!new LinkedHashSet<>(paths).containsAll(changes.keySet())) {
            throw failed("sandbox_coverage");
        }
        String frozenVersion = candidate.projectVersion().value();
        ProjectManifestResponse manifest = projects.manifest(
                userId, projectId);
        if (!projectId.equals(manifest.projectId())
                || !frozenVersion.equals(manifest.version())) {
            throw failed("project_version");
        }
        Map<String, ProjectFileEntry> frozenFiles = frozenManifest(manifest);
        Map<String, String> expectedPresent = new LinkedHashMap<>();
        Set<String> expectedAbsent = new LinkedHashSet<>();
        boolean requiresStateProof = false;
        for (String path : paths) {
            CandidateFileChange change = changes.get(path);
            if (change == null) {
                if (!frozenFiles.containsKey(path)) {
                    throw failed("sandbox_extra_path");
                }
                ProjectFileResponse base = readFrozen(
                        userId, projectId, frozenFiles, path);
                expectedPresent.put(path, base.content());
                continue;
            }
            ProjectFileResponse base = frozenFiles.containsKey(path)
                    ? readFrozen(userId, projectId, frozenFiles, path)
                    : null;
            switch (change.type()) {
                case ADD -> {
                    requiresStateProof = true;
                    boolean conflicts = frozenFiles.keySet().stream()
                            .anyMatch(value -> conflictKey(value).equals(
                            conflictKey(path)));
                    if (base != null || conflicts
                            || change.baseFileHash() != null
                            || !validResult(change)) {
                        throw failed("candidate_add");
                    }
                    expectedPresent.put(
                            path, change.candidateText().text());
                }
                case MODIFY -> {
                    if (base == null || change.baseFileHash() == null
                            || !change.baseFileHash().sha256().equals(
                            base.sha256()) || !validResult(change)) {
                        throw failed("candidate_modify");
                    }
                    expectedPresent.put(
                            path, change.candidateText().text());
                }
                case DELETE -> {
                    requiresStateProof = true;
                    if (base == null || change.baseFileHash() == null
                            || !change.baseFileHash().sha256().equals(
                            base.sha256())
                            || change.candidateText() != null
                            || change.resultFileHash() != null) {
                        throw failed("candidate_delete");
                    }
                    expectedAbsent.add(path);
                }
            }
        }
        if (!frozenVersion.equals(projects.manifest(
                userId, projectId).version())) {
            throw failed("project_version");
        }
        String expectedState = V2SandboxInputFingerprint
                .stateArtifactReference(
                        expectedPresent, expectedAbsent).value();
        List<String> stateRefs = versionedArtifacts(
                receipt, "sandbox-input-states-v2:");
        boolean matched;
        if (!stateRefs.isEmpty()) {
            matched = stateRefs.size() == 1
                    && stateRefs.get(0).equals(expectedState);
        } else if (!requiresStateProof) {
            String legacy = V2SandboxInputFingerprint
                    .artifactReference(expectedPresent).value();
            List<String> legacyRefs = versionedArtifacts(
                    receipt, "sandbox-inputs:");
            matched = legacyRefs.size() == 1
                    && legacyRefs.get(0).equals(legacy);
        } else {
            matched = false;
        }
        if (!matched) {
            throw failed("sandbox_input_fingerprint");
        }
        List<VerifiedInputState> verifiedInputs = new ArrayList<>();
        expectedPresent.forEach((path, content) -> verifiedInputs.add(
                new VerifiedInputState(path, InputPresence.PRESENT,
                        hash(content))));
        expectedAbsent.forEach(path -> verifiedInputs.add(
                new VerifiedInputState(path, InputPresence.ABSENT, null)));
        return new VerificationProof(
                receipt.id().value(), paths, sandboxArgv(sandbox),
                output(receipt.standardOutput()),
                output(receipt.standardError()),
                receipt.exitCode().orElseThrow(), receipt.startedAt(),
                receipt.endedAt(), verifiedInputs);
    }

    private static List<String> versionedArtifacts(
            io.paperagent.v2.contracts.ExecutionReceipt receipt,
            String prefix) {
        return receipt.artifactReferences().stream()
                .map(reference -> reference.value())
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    private static Map<String, ProjectFileEntry> frozenManifest(
            ProjectManifestResponse manifest) {
        Map<String, ProjectFileEntry> result = new LinkedHashMap<>();
        Set<String> folded = new LinkedHashSet<>();
        if (manifest.files() == null) {
            throw failed("project_manifest");
        }
        for (ProjectFileEntry entry : manifest.files()) {
            String path = canonicalPath(entry.path(), "project_manifest");
            if (!folded.add(conflictKey(path))
                    || entry.sha256() == null
                    || !entry.sha256().matches("[a-f0-9]{64}")) {
                throw failed("project_manifest");
            }
            result.put(path, entry);
        }
        return Map.copyOf(result);
    }

    private ProjectFileResponse readFrozen(
            Long userId, Long projectId,
            Map<String, ProjectFileEntry> manifest, String path) {
        ProjectFileEntry entry = manifest.get(path);
        if (entry == null) throw failed("project_file");
        ProjectFileResponse file = projects.readFile(
                userId, projectId, path);
        if (!path.equals(file.path())
                || !entry.sha256().equals(file.sha256())
                || !file.sha256().equals(hash(file.content()))) {
            throw failed("project_file");
        }
        return file;
    }

    private static boolean validResult(CandidateFileChange change) {
        return change.candidateText() != null
                && change.resultFileHash() != null
                && change.resultFileHash().sha256().equals(
                hash(change.candidateText().text()));
    }

    private static Map<String, CandidateFileChange> candidateChanges(
            CandidateArtifactResponse candidate) {
        Map<String, CandidateFileChange> result = new LinkedHashMap<>();
        Set<String> folded = new LinkedHashSet<>();
        for (CandidateFileChange change : candidate.changes()) {
            String path = canonicalPath(
                    change.relativePath().value(), "candidate_paths");
            if (!folded.add(conflictKey(path))
                    || result.putIfAbsent(path, change) != null
                    || !change.projectVersion().equals(
                    candidate.projectVersion())) {
                throw failed("candidate_paths");
            }
        }
        if (result.isEmpty()) {
            throw failed("candidate_paths");
        }
        return Map.copyOf(result);
    }

    private static List<String> sandboxPaths(
            V2EffectHistorySource.Entry sandbox) {
        Map<String, ContractValue> values = sandbox.intent().intent()
                .arguments().values();
        if (!(values.get("paths") instanceof ListValue raw)
                || raw.values().isEmpty()) {
            throw failed("sandbox_paths");
        }
        List<String> paths = new ArrayList<>();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (ContractValue value : raw.values()) {
            if (!(value instanceof TextValue text)) {
                throw failed("sandbox_paths");
            }
            String path = canonicalPath(text.value(), "sandbox_paths");
            if (!distinct.add(conflictKey(path))) {
                throw failed("sandbox_paths");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static Set<String> candidatePaths(
            CandidateArtifactResponse candidate) {
        return candidateChanges(candidate).keySet();
    }

    private static String canonicalPath(String value, String stage) {
        try {
            String path = new ProjectPath(value).value();
            if (!path.equals(value)) throw failed(stage);
            return path;
        } catch (RuntimeException invalid) {
            throw failed(stage);
        }
    }

    private static String conflictKey(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static List<String> sandboxArgv(
            V2EffectHistorySource.Entry sandbox) {
        ContractValue value = sandbox.intent().intent()
                .arguments().values().get("argv");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof ListValue raw)) {
            throw failed("sandbox_argv");
        }
        List<String> argv = new ArrayList<>();
        for (ContractValue item : raw.values()) {
            if (!(item instanceof TextValue text)) {
                throw failed("sandbox_argv");
            }
            argv.add(text.value());
        }
        return List.copyOf(argv);
    }

    private static String output(OutputCapture capture) {
        if (capture == null) {
            return "";
        }
        return capture.inlineText().orElseGet(() -> capture.artifactRef()
                .map(reference -> "Stored output: " + reference.value())
                .orElse(""));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IllegalStateException failed(String stage) {
        return new IllegalStateException(
                "Agent Candidate auto-application failed: " + stage);
    }

    public record VerificationProof(
            String receiptId,
            List<String> paths,
            List<String> argv,
            String standardOutput,
            String standardError,
            int exitCode,
            java.time.Instant startedAt,
            java.time.Instant endedAt,
            List<VerifiedInputState> verifiedInputs) {
        public VerificationProof {
            paths = List.copyOf(paths);
            argv = List.copyOf(argv);
            java.util.Objects.requireNonNull(startedAt, "startedAt");
            java.util.Objects.requireNonNull(endedAt, "endedAt");
            if (verifiedInputs == null || verifiedInputs.isEmpty()) {
                throw new IllegalArgumentException(
                        "verifiedInputs are required");
            }
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            for (VerifiedInputState input : verifiedInputs) {
                if (input == null
                        || !distinct.add(conflictKey(input.path()))) {
                    throw new IllegalArgumentException(
                            "verifiedInputs contain duplicate paths");
                }
            }
            verifiedInputs = verifiedInputs.stream()
                    .sorted(Comparator.comparing(
                            VerifiedInputState::path))
                    .toList();
        }
    }

    public enum InputPresence { PRESENT, ABSENT }

    public record VerifiedInputState(
            String path,
            InputPresence presence,
            String contentSha256) {
        public VerifiedInputState {
            path = canonicalPath(path, "verified_input");
            java.util.Objects.requireNonNull(presence, "presence");
            if ((presence == InputPresence.PRESENT
                    && (contentSha256 == null
                    || !contentSha256.matches("[a-f0-9]{64}")))
                    || (presence == InputPresence.ABSENT
                    && contentSha256 != null)) {
                throw new IllegalArgumentException(
                        "verified input presence and content disagree");
            }
        }
    }

    private record ChainProofScope(String taskId, String workspaceId) {
        private ChainProofScope {
            java.util.Objects.requireNonNull(taskId, "taskId");
            java.util.Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }
}
