package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface ProductExecutionStartJpaRepository
        extends JpaRepository<ProductExecutionStartEntity, String> {
    Optional<ProductExecutionStartEntity> findByStartEventId(String eventId);
}
