package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface ProductEffectOutcomeResultJpaRepository
        extends JpaRepository<ProductEffectOutcomeResultEntity, String> {
    Optional<ProductEffectOutcomeResultEntity> findByReceiptId(String receiptId);
}
