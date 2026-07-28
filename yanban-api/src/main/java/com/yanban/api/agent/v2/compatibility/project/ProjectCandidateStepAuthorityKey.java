package com.yanban.api.agent.v2.compatibility.project;

import java.io.Serializable;
import java.util.Objects;

public class ProjectCandidateStepAuthorityKey implements Serializable {
    private String planId;
    private String stepId;
    public ProjectCandidateStepAuthorityKey() {}
    ProjectCandidateStepAuthorityKey(String planId, String stepId) {
        this.planId = planId; this.stepId = stepId;
    }
    @Override public boolean equals(Object other) {
        return other instanceof ProjectCandidateStepAuthorityKey key
                && Objects.equals(planId, key.planId) && Objects.equals(stepId, key.stepId);
    }
    @Override public int hashCode() { return Objects.hash(planId, stepId); }
}
