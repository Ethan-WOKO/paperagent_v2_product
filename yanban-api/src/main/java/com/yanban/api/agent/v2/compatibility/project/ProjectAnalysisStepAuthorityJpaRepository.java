package com.yanban.api.agent.v2.compatibility.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectAnalysisStepAuthorityJpaRepository
        extends JpaRepository<ProjectAnalysisStepAuthorityEntity,
        ProjectAnalysisStepAuthorityKey> {
    Optional<ProjectAnalysisStepAuthorityEntity> findByPlanIdAndStepId(
            String planId, String stepId);
    List<ProjectAnalysisStepAuthorityEntity> findByPlanIdOrderByStepId(
            String planId);
}
