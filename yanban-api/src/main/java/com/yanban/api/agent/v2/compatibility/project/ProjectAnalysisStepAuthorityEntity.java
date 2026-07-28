package com.yanban.api.agent.v2.compatibility.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_v2_project_analysis_steps")
@IdClass(ProjectAnalysisStepAuthorityKey.class)
class ProjectAnalysisStepAuthorityEntity {
    @Id
    @Column(name = "plan_id", length = 128)
    private String planId;
    @Id
    @Column(name = "step_id", length = 128)
    private String stepId;
    @Column(name = "effect_kind", nullable = false, length = 64)
    private String effectKind;
    @Column(name = "argument_json", nullable = false, columnDefinition = "LONGTEXT")
    private String argumentJson;
    @Column(name = "argument_sha256", nullable = false, length = 64)
    private String argumentSha256;

    protected ProjectAnalysisStepAuthorityEntity() {
    }

    ProjectAnalysisStepAuthorityEntity(
            String planId, String stepId, String effectKind,
            String argumentJson, String argumentSha256) {
        this.planId = planId;
        this.stepId = stepId;
        this.effectKind = effectKind;
        this.argumentJson = argumentJson;
        this.argumentSha256 = argumentSha256;
    }

    String planId() { return planId; }
    String stepId() { return stepId; }
    String effectKind() { return effectKind; }
    String argumentJson() { return argumentJson; }
    String argumentSha256() { return argumentSha256; }
}
