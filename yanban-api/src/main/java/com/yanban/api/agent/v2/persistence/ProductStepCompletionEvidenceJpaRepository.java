package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ProductStepCompletionEvidenceJpaRepository extends JpaRepository<
        ProductStepCompletionEvidenceEntity, ProductStepCompletionEvidenceId> {
    List<ProductStepCompletionEvidenceEntity>
            findAllByCompletionEventIdOrderByOrdinal(String completionEventId);
}
