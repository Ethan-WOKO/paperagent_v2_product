package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ProductStepActivationJpaRepository
        extends JpaRepository<ProductStepActivationEntity, String> {
    List<ProductStepActivationEntity> findAllByPlanId(String planId);

    List<ProductStepActivationEntity>
            findAllByPlanIdOrderBySourceEventSequenceAsc(String planId);

    Optional<ProductStepActivationEntity> findByPlanIdAndStepId(
            String planId, String stepId);
}
