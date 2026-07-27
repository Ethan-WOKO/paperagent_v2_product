package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ProductEffectIntentJpaRepository
        extends JpaRepository<ProductEffectIntentEntity, String> {
    List<ProductEffectIntentEntity> findAllByPlanId(String planId);
}
