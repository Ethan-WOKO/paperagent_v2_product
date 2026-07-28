package com.yanban.api.agent.v2.compatibility.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectCandidateStepAuthorityJpaRepository extends
        JpaRepository<ProjectCandidateStepAuthorityEntity, ProjectCandidateStepAuthorityKey> {
    Optional<ProjectCandidateStepAuthorityEntity> findByPlanIdAndStepId(String planId, String stepId);
}
