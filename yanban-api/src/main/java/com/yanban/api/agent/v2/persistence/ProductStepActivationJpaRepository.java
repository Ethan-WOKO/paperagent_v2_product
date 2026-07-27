package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ProductStepActivationJpaRepository
        extends JpaRepository<ProductStepActivationEntity, String> {
    List<ProductStepActivationEntity> findAllByPlanId(String planId);
}
