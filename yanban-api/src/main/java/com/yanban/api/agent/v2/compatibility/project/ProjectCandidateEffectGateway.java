package com.yanban.api.agent.v2.compatibility.project;

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
}
