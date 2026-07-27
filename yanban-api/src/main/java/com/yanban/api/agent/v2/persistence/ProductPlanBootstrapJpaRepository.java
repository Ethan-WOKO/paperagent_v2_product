package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface ProductPlanBootstrapJpaRepository
        extends JpaRepository<ProductPlanBootstrapEntity, String> {
    Optional<ProductPlanBootstrapEntity> findByTaskFrameId(String taskFrameId);
}
