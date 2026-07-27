package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface ProductPlanExecutionContextJpaRepository
        extends JpaRepository<ProductPlanExecutionContextEntity, String> {
    Optional<ProductPlanExecutionContextEntity> findByWorkspaceId(String workspaceId);
}
