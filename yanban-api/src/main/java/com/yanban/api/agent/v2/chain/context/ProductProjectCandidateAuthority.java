package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.util.Objects;

/** Exact Candidate artifact fenced by one frozen Context revision. */
final class ProductProjectCandidateAuthority {
    private ProductProjectCandidateAuthority() {
    }

    static CandidateArtifactResponse exactOrNull(
            ContextRevisionRecord revision,
            TaskRecord task,
            ChainWorkflowRepository workflow,
            CandidateChangeArtifactService candidates) {
        ExactCandidate exact = exactBindingOrNull(
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                revision, task, workflow, candidates);
        return exact == null ? null : exact.candidate();
    }

    static ExactCandidate exactBindingOrNull(
            ChainContextModule module,
            ContextRevisionRecord revision,
            TaskRecord task,
            ChainWorkflowRepository workflow,
            CandidateChangeArtifactService candidates) {
        Objects.requireNonNull(revision, "revision");
        if (revision.candidateArtifactId() == null) return null;
        var bindings = workflow.findWorkspaceCandidates(task.taskId()).stream()
                .filter(value -> value.artifactId()
                        == revision.candidateArtifactId())
                .filter(value -> value.candidateFingerprint().equals(
                        revision.candidateFingerprint()))
                .filter(value -> value.baseProjectVersion().equals(
                        revision.projectVersion()))
                .filter(value -> revision.workspaceId() == null
                        || value.workspaceId().equals(revision.workspaceId()))
                .toList();
        if (bindings.size() != 1) {
            throw blocked(module,
                    "Candidate binding is missing or ambiguous");
        }
        CandidateArtifactResponse candidate = candidates.getCurrent(
                task.userId(), revision.candidateArtifactId());
        var binding = bindings.get(0);
        boolean exact = candidate.artifactId().equals(
                        revision.candidateArtifactId())
                && candidate.projectId() == task.projectId()
                && candidate.projectVersion().value().equals(
                        revision.projectVersion())
                && candidate.fingerprint().sha256().equals(
                        revision.candidateFingerprint())
                && candidate.fingerprint().sha256().equals(
                        binding.candidateFingerprint())
                && ProductProjectInputProjectionCodec.candidateDiffDigest(
                        candidate).equals(binding.diffDigest());
        if (!exact) throw blocked(module,
                "Candidate authority identity changed");
        return new ExactCandidate(binding, candidate);
    }

    record ExactCandidate(
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .WorkspaceCandidateRecord binding,
            CandidateArtifactResponse candidate) {
    }

    private static RuntimeException blocked(
            ChainContextModule module, String reason) {
        return ProductChainContextProjectionSupport.blocked(
                module, reason);
    }
}
