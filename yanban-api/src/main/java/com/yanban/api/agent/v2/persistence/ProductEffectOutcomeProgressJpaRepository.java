package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ProductEffectOutcomeProgressJpaRepository
        extends JpaRepository<ProductEffectOutcomeProgressEntity, String> {
    List<ProductEffectOutcomeProgressEntity>
            findAllByToolCallIdOrderBySequenceAsc(String toolCallId);

    Optional<ProductEffectOutcomeProgressEntity>
            findFirstByToolCallIdOrderBySequenceDesc(String toolCallId);
}
