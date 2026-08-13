package com.yanban.api.agent.v2.chain.context;

import com.yanban.core.agent.sandbox.CandidateFileChange;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure role values for one exact WorkspaceCandidate cut. */
final class ProductWorkspaceCandidateProjectionValues {
    private static final ChainContextModule MODULE =
            ChainContextModule.WORKSPACE_AND_CANDIDATE;

    private ProductWorkspaceCandidateProjectionValues() {
    }

    static Values create(
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .ContextRevisionRecord revision,
            List<String> requiredFields,
            ProductWorkspaceCandidateAuthority.Snapshot source) {
        var binding = source.binding();
        var candidate = source.candidate();
        ChainContextValue candidateFiles = ProductProjectInputProjectionCodec
                .candidateFiles(candidate);
        ChainContextValue manifest = ChainContextValue.object(Map.of(
                "projectManifest", ProductProjectInputProjectionCodec
                        .manifestRoot(source.manifest()),
                "candidateOverlay", candidateFiles,
                "overlayAppliedToPublishedVersion",
                ChainContextValue.bool(source.published()),
                "complete", ChainContextValue.bool(true)));
        ChainContextValue identity = ChainContextValue.object(Map.of(
                "workspaceRef", ref(binding.workspaceId()),
                "workspaceCandidateRef", ref(
                        binding.workspaceCandidateId()),
                "actionRef", ref(binding.actionId()),
                "baseProjectVersion", ref(binding.baseProjectVersion()),
                "artifactId", ChainContextValue.number(binding.artifactId()),
                "candidateFingerprint", ref(binding.candidateFingerprint()),
                "bindingSequence", ChainContextValue.number(
                        source.bindingSequence())));
        ChainContextValue diff = diff(candidateFiles, source);
        ChainContextValue artifacts = ChainContextValue.array(List.of(
                ChainContextValue.object(Map.of(
                        "artifactId", ChainContextValue.number(
                                binding.artifactId()),
                        "candidateFingerprint",
                        ref(binding.candidateFingerprint()),
                        "workspaceCandidateRef",
                        ref(binding.workspaceCandidateId()),
                        "diffDigest", ref(binding.diffDigest())))));
        Map<String, ChainContextValue> available = new TreeMap<>();
        available.put("workspace.manifest.complete", manifest);
        available.put("workspace.currentState", identity);
        available.put("workspace.diffSummary", diff);
        available.put("workspace.exactVersion", identity);
        available.put("workspace.targetAndModifiedFileExpansion",
                ChainContextValue.object(Map.of(
                        "formalCandidateTargets", candidateFiles,
                        "modifiedFiles", candidateFiles)));
        available.put("workspace.reviewedCandidate", identity);
        available.put("workspace.diff", diff);
        available.put("workspace.artifacts", artifacts);
        available.put("workspace.affectedFileExpansion", candidateFiles);
        available.put("workspace.finalArtifactOrCandidate",
                ChainContextValue.object(Map.of(
                        "identity", identity, "artifacts", artifacts)));
        available.put("workspace.deliveryManifest", manifest);
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : requiredFields) {
            ChainContextValue value = available.get(field);
            if (value == null) throw blocked(
                    "unsupported required Workspace field: " + field);
            fields.put(field, value);
        }
        return new Values(
                Map.of(
                        "workspaceConfirmationFingerprint",
                        ref(binding.versionFenceSha256()),
                        "candidateBindingSequence",
                        ChainContextValue.number(source.bindingSequence()),
                        "artifactFingerprintAndDiff",
                        ChainContextValue.object(Map.of(
                                "artifactId", ChainContextValue.number(
                                        binding.artifactId()),
                                "candidateFingerprint",
                                ref(binding.candidateFingerprint()),
                                "diffDigest", ref(binding.diffDigest())))),
                Map.of(
                        "projectVersion", ref(
                                source.manifest().version()),
                        "workspace", ref(binding.workspaceId()),
                        "candidate", ref(binding.workspaceCandidateId())),
                Map.of(
                        "workspaceCandidateRef",
                        ref(binding.workspaceCandidateId()),
                        "actionRef", ref(binding.actionId()),
                        "role", ChainContextValue.text(
                                revision.role().name())),
                fields);
    }

    private static ChainContextValue diff(
            ChainContextValue candidateFiles,
            ProductWorkspaceCandidateAuthority.Snapshot source) {
        long adds = source.candidate().reviewDiff().entries().stream()
                .filter(value -> value.type() == CandidateFileChange.Type.ADD)
                .count();
        long modifies = source.candidate().reviewDiff().entries().stream()
                .filter(value -> value.type()
                        == CandidateFileChange.Type.MODIFY).count();
        long deletes = source.candidate().reviewDiff().entries().stream()
                .filter(value -> value.type()
                        == CandidateFileChange.Type.DELETE).count();
        return ChainContextValue.object(Map.of(
                "diffDigest", ref(source.binding().diffDigest()),
                "addCount", ChainContextValue.number(adds),
                "modifyCount", ChainContextValue.number(modifies),
                "deleteCount", ChainContextValue.number(deletes),
                "files", candidateFiles));
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    record Values(
            Map<String, ChainContextValue> sourceVersion,
            Map<String, ChainContextValue> readBoundary,
            Map<String, ChainContextValue> parameters,
            Map<String, ChainContextValue> fields) {
    }
}
