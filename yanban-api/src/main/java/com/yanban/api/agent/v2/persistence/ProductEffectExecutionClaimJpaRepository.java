package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProductEffectExecutionClaimJpaRepository
        extends JpaRepository<ProductEffectExecutionClaimEntity, String> {
}
