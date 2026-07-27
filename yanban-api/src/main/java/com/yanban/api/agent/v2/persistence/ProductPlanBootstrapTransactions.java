package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
class ProductPlanBootstrapTransactions {
    private final ProductPlanBootstrapJpaRepository repository;
    private final EntityManager entityManager;

    ProductPlanBootstrapTransactions(
            ProductPlanBootstrapJpaRepository repository,
            EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ProductPlanBootstrapEntity> findByPlanId(String planId) {
        return repository.findById(planId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ProductPlanBootstrapEntity> findByTaskFrameId(String taskFrameId) {
        return repository.findByTaskFrameId(taskFrameId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductPlanBootstrapEntity insert(ProductPlanBootstrapEntity entity) {
        // The identifier is assigned by the V2 aggregate. JpaRepository.save()
        // may therefore choose merge and overwrite a concurrently inserted row.
        // Persist is intentionally used to guarantee insert-only authority.
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
