package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

interface ProductPlanBootstrapJpaRepository
        extends JpaRepository<ProductPlanBootstrapEntity, String> {
    Optional<ProductPlanBootstrapEntity> findByTaskFrameId(String taskFrameId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bootstrap from ProductPlanBootstrapEntity bootstrap "
            + "where bootstrap.planId = :planId")
    Optional<ProductPlanBootstrapEntity> lockByPlanId(@Param("planId") String planId);
}
