package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ProductStepCompletionJpaRepository
        extends JpaRepository<ProductStepCompletionEntity, String> {
    List<ProductStepCompletionEntity> findAllByPlanId(String planId);
}
