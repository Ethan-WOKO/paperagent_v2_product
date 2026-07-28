package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

interface ProductActiveStepReplanJpaRepository
        extends JpaRepository<ProductActiveStepReplanEntity, String> {
    Optional<ProductActiveStepReplanEntity> findBySupersessionEventId(
            String eventId);

    Optional<ProductActiveStepReplanEntity> findByReplanEventId(String eventId);

    List<ProductActiveStepReplanEntity>
            findAllByPlanIdOrderBySourceEventSequenceAsc(String planId);
}
