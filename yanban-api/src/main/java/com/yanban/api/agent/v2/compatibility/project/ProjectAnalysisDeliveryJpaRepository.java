package com.yanban.api.agent.v2.compatibility.project;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProjectAnalysisDeliveryJpaRepository
        extends JpaRepository<ProjectAnalysisDeliveryEntity,
        ProjectAnalysisDeliveryKey> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ProjectAnalysisDeliveryEntity d where d.id = :id")
    Optional<ProjectAnalysisDeliveryEntity> findLocked(
            @Param("id") ProjectAnalysisDeliveryKey id);
}
