package com.yanban.api.agent.v2.compatibility.project;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface ProjectCandidateDeliveryJpaRepository
        extends JpaRepository<ProjectCandidateDeliveryEntity, ProjectCandidateDeliveryKey> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ProjectCandidateDeliveryEntity d where d.id = :id")
    Optional<ProjectCandidateDeliveryEntity> findLocked(@Param("id") ProjectCandidateDeliveryKey id);
    Optional<ProjectCandidateDeliveryEntity> findByPlanId(String planId);
}
