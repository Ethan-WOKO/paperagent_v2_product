package com.yanban.api.agent.v2.compatibility.project;

import jakarta.persistence.*;

@Entity
@Table(name = "agent_v2_project_candidate_steps")
@IdClass(ProjectCandidateStepAuthorityKey.class)
class ProjectCandidateStepAuthorityEntity {
    @Id @Column(name = "plan_id", length = 128) private String planId;
    @Id @Column(name = "step_id", length = 128) private String stepId;
    @Column(name = "effect_kind", nullable = false, length = 64) private String effectKind;
    @Column(name = "authority_json", nullable = false, columnDefinition = "LONGTEXT")
    private String authorityJson;
    @Column(name = "authority_sha256", nullable = false, length = 64)
    private String authoritySha256;
    protected ProjectCandidateStepAuthorityEntity() {}
    ProjectCandidateStepAuthorityEntity(String planId, String stepId, String effectKind,
                                        String authorityJson, String authoritySha256) {
        this.planId = planId; this.stepId = stepId; this.effectKind = effectKind;
        this.authorityJson = authorityJson; this.authoritySha256 = authoritySha256;
    }
    String effectKind() { return effectKind; }
    String authorityJson() { return authorityJson; }
    String authoritySha256() { return authoritySha256; }
}
