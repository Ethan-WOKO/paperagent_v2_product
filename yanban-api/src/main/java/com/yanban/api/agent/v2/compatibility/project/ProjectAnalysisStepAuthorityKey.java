package com.yanban.api.agent.v2.compatibility.project;

import java.io.Serializable;
import java.util.Objects;

public class ProjectAnalysisStepAuthorityKey implements Serializable {
    private String planId;
    private String stepId;

    public ProjectAnalysisStepAuthorityKey() {
    }

    ProjectAnalysisStepAuthorityKey(String planId, String stepId) {
        this.planId = planId;
        this.stepId = stepId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectAnalysisStepAuthorityKey key
                && Objects.equals(planId, key.planId)
                && Objects.equals(stepId, key.stepId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planId, stepId);
    }
}
