package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ProductStepCompletionJpaRepository
        extends JpaRepository<ProductStepCompletionEntity, String> {
    List<ProductStepCompletionEntity> findAllByPlanId(String planId);

    List<ProductStepCompletionEntity>
            findAllByPlanIdOrderBySourceEventSequenceAsc(String planId);

    Optional<ProductStepCompletionEntity>
            findByPlanIdAndStepIdAndActivationEventId(
                    String planId, String stepId, String activationEventId);
}
