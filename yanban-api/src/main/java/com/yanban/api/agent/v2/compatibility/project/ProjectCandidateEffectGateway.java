package com.yanban.api.agent.v2.compatibility.project;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProjectCandidateEffectGateway {
    private final ProjectCandidateDeliveryTransactions transactions;
    ProjectCandidateEffectGateway(ProjectCandidateDeliveryTransactions transactions) {
        this.transactions = transactions;
    }
    public ProjectCandidateEffectAuthority require(String planId, String stepId) {
        return transactions.authority(planId, stepId);
    }
    public void bindCandidate(String planId, Long artifactId,
                              String candidateFingerprint, String diffFingerprint) {
        transactions.bindCandidate(planId, artifactId, candidateFingerprint, diffFingerprint);
    }
    public void bindPrepared(String planId, Map<String, String> replacements,
                             String diffFingerprint) {
        bindPrepared(planId, replacements, java.util.List.of(), diffFingerprint);
    }
    public void bindPrepared(String planId, Map<String, String> replacements,
            java.util.List<String> mavenCoordinates, String diffFingerprint) {
        transactions.bindPrepared(planId, replacements, mavenCoordinates, diffFingerprint);
    }
    public PreparedCandidate requirePrepared(String planId) {
        var prepared = transactions.prepared(planId);
        return new PreparedCandidate(prepared.replacements(),
                prepared.mavenCoordinates(), prepared.diffFingerprint());
    }
    public record PreparedCandidate(
            Map<String, String> replacements, java.util.List<String> mavenCoordinates,
            String diffFingerprint) {
        public PreparedCandidate {
            replacements = Map.copyOf(replacements);
            mavenCoordinates = java.util.List.copyOf(mavenCoordinates);
        }
        public PreparedCandidate(Map<String, String> replacements, String diffFingerprint) {
            this(replacements, java.util.List.of(), diffFingerprint);
        }
    }
}
