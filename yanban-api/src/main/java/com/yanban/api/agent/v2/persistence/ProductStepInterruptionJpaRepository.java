package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ProductStepInterruptionJpaRepository
        extends JpaRepository<ProductStepInterruptionEntity, String> {
    List<ProductStepInterruptionEntity> findAllByPlanId(String planId);
}
