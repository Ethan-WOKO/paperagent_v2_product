package com.yanban.api.agent.v2.compatibility.project;

import org.springframework.stereotype.Service;

@Service
public class ProjectAnalysisAuthoritySource {
    private final ProjectAnalysisDeliveryTransactions transactions;

    ProjectAnalysisAuthoritySource(
            ProjectAnalysisDeliveryTransactions transactions) {
        this.transactions = transactions;
    }

    public ProjectAnalysisEffectAuthority require(
            String planId, String stepId) {
        var value = transactions.authority(planId, stepId);
        return new ProjectAnalysisEffectAuthority(
                value.effectKind(), value.argumentJson(),
                value.argumentSha256());
    }
}
