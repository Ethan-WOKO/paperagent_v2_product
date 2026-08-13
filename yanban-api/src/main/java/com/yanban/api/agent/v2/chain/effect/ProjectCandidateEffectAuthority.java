package com.yanban.api.agent.v2.chain.effect;

import java.util.List;

public record ProjectCandidateEffectAuthority(
        String kind,
        String authorityJson,
        String authoritySha256,
        Long userId,
        Long projectId,
        Long sessionId,
        Long turnId,
        String projectVersion,
        String objective,
        List<String> paths,
        RepairAuthority repair,
        ChainActionWorkspaceAuthority chainAction) {
    public ProjectCandidateEffectAuthority {
        paths = List.copyOf(paths);
    }
    public ProjectCandidateEffectAuthority(
            String kind, String authorityJson, String authoritySha256,
            Long userId, Long projectId, Long sessionId, Long turnId,
            String projectVersion, String objective, List<String> paths,
            RepairAuthority repair) {
        this(kind, authorityJson, authoritySha256, userId, projectId,
                sessionId, turnId, projectVersion, objective, paths,
                repair, null);
    }
    public ProjectCandidateEffectAuthority(String kind, String authorityJson, String authoritySha256,
            Long userId, Long projectId, Long sessionId, Long turnId, String projectVersion,
            String objective, List<String> paths) {
        this(kind, authorityJson, authoritySha256, userId, projectId, sessionId, turnId,
                projectVersion, objective, paths, null, null);
    }

    public record RepairAuthority(String sourceValidationId, long sourceCandidateArtifactId,
            String sourceCandidateFingerprint, int selectedChangeIndex, String selectedPath,
            String failedReceiptDigest, String originalProjectVersion, int attempt, int maxAttempts,
            java.util.Map<String, String> sourceReplacements, String sourceReplacementsSha256,
            String compilerDiagnostic) {
        public RepairAuthority {
            sourceReplacements = java.util.Map.copyOf(sourceReplacements);
        }
    }

}
