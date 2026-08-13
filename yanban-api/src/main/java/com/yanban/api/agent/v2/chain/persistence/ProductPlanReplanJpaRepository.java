package com.yanban.api.agent.v2.chain.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ProductPlanReplanJpaRepository
        extends JpaRepository<ProductPlanReplanEntity, String> {
    Optional<ProductPlanReplanEntity> findByReplanEventId(String eventId);

    List<ProductPlanReplanEntity>
            findAllByPlanIdOrderBySourceEventSequenceAsc(String planId);
}
