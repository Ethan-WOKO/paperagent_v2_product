package com.yanban.api.agent.v2.result;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface V2StepResultJpaRepository
        extends JpaRepository<V2StepResultEntity, String> {
    Optional<V2StepResultEntity>
            findByActivationEventIdAndSourceAndProposedSha256(
                    String activationEventId, String source,
                    String proposedSha256);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<V2StepResultEntity> findLockedByResultId(String resultId);

    List<V2StepResultEntity> findAllByPlanIdOrderByCreatedAtAsc(
            String planId);

    Optional<V2StepResultEntity>
            findFirstByActivationEventIdAndStatusOrderByUpdatedAtDesc(
                    String activationEventId, String status);

    Optional<V2StepResultEntity>
            findFirstByActivationEventIdOrderByUpdatedAtDesc(
                    String activationEventId);
}
