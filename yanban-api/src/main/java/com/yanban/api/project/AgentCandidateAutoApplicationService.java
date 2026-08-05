package com.yanban.api.project;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.V2SandboxInputFingerprint;
import com.yanban.api.agent.v2.effect.project.NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.PlanId;
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
import java.util.Map;
import java.util.Set;
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
    private final ProjectRevisionWorkflowService revisions;

    public AgentCandidateAutoApplicationService(
            NaturalLanguageCandidateAuthorityStore authorities,
            CandidateChangeArtifactService candidates,
            ProjectService projects,
            V2EffectHistorySource effects,
            ProjectRevisionWorkflowService revisions) {
        this.authorities = authorities;
        this.candidates = candidates;
        this.projects = projects;
        this.effects = effects;
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
        return proof(authorities.require(planId), planId, artifactId);
    }

    private VerificationProof proof(
            com.yanban.api.agent.v2.compatibility.project
                    .ProjectCandidateEffectAuthority authority,
            String planId, Long artifactId) {
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
                planId, candidate);
    }

    private VerificationProof requireFinalSandbox(
            Long userId, Long projectId, String planId,
            CandidateArtifactResponse candidate) {
        List<V2EffectHistorySource.Entry> history = effects.inspect(
                new PlanId(planId));
        int lastCandidate = -1;
        int lastSandbox = -1;
        V2EffectHistorySource.Entry candidateComposition = null;
        V2EffectHistorySource.Entry sandbox = null;
        for (int index = 0; index < history.size(); index++) {
            var entry = history.get(index);
            String kind = entry.intent().intent().kind();
            if (CANDIDATE_KIND.equals(kind) && entry.successful()) {
                lastCandidate = index;
                candidateComposition = entry;
            }
            if (SANDBOX_KIND.equals(kind)) {
                lastSandbox = index;
                sandbox = entry;
            }
        }
        if (lastCandidate < 0 || candidateComposition == null
                || lastSandbox <= lastCandidate
                || sandbox == null || !sandbox.completed()) {
            throw failed("sandbox_order");
        }
        var receipt = sandbox.result().receipt();
        if (receipt.status() != ReceiptStatus.SUCCESS
                || receipt.exitCode().orElse(-1) != 0) {
            throw failed("sandbox_result");
        }

        List<String> paths = sandboxPaths(sandbox);
        Set<String> candidatePaths = candidatePaths(candidate);
        if (!new LinkedHashSet<>(paths).containsAll(candidatePaths)) {
            throw failed("sandbox_coverage");
        }
        Map<String, CandidateFileChange> changes = new LinkedHashMap<>();
        for (CandidateFileChange change : candidate.changes()) {
            if (change.type() != CandidateFileChange.Type.MODIFY
                    || changes.put(change.relativePath().value(), change)
                            != null) {
                throw failed("candidate_change_type");
            }
        }
        Map<String, String> expectedFiles = new LinkedHashMap<>();
        for (String path : paths) {
            CandidateFileChange change = changes.get(path);
            if (change == null) {
                ProjectFileResponse base = projects.readFile(
                        userId, projectId, path);
                expectedFiles.put(path, base.content());
                continue;
            }
            if (change.baseFileHash() == null
                    || change.candidateText() == null) {
                throw failed("candidate_base");
            }
            expectedFiles.put(path, change.candidateText().text());
        }
        String expected = V2SandboxInputFingerprint
                .artifactReference(expectedFiles).value();
        boolean matched = receipt.artifactReferences().stream()
                .anyMatch(reference -> expected.equals(reference.value()));
        if (!matched) {
            throw failed("sandbox_input_fingerprint");
        }
        Map<String, String> replacements = new LinkedHashMap<>();
        for (CandidateFileChange change : candidate.changes()) {
            replacements.put(change.relativePath().value(),
                    change.candidateText().text());
        }
        return new VerificationProof(
                receipt.id().value(), paths, sandboxArgv(sandbox),
                output(receipt.standardOutput()),
                output(receipt.standardError()),
                receipt.exitCode().orElseThrow(), replacements);
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
            String path = new ProjectPath(text.value()).value();
            if (!distinct.add(path)) {
                throw failed("sandbox_paths");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static Set<String> candidatePaths(
            CandidateArtifactResponse candidate) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (CandidateFileChange change : candidate.changes()) {
            if (!paths.add(change.relativePath().value())) {
                throw failed("candidate_paths");
            }
        }
        return Set.copyOf(paths);
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
            Map<String, String> replacements) {
        public VerificationProof {
            paths = List.copyOf(paths);
            argv = List.copyOf(argv);
            replacements = Map.copyOf(replacements);
        }
    }
}
