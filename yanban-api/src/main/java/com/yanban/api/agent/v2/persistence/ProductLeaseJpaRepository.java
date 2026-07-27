package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface ProductLeaseJpaRepository
        extends JpaRepository<ProductLeaseEntity, ProductLeaseId> {
    Optional<ProductLeaseEntity> findFirstByPlanIdOrderByFencingTokenDesc(String planId);

    Optional<ProductLeaseEntity> findByLeaseToken(String leaseToken);
}
