package com.yanban.api.project;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CandidateSandboxValidationRepository extends JpaRepository<CandidateSandboxValidation, Long> {
    Optional<CandidateSandboxValidation> findByValidationId(String validationId);
    Optional<CandidateSandboxValidation> findByValidationIdAndUserIdAndProjectId(String validationId, Long userId, Long projectId);
    Optional<CandidateSandboxValidation> findByUserIdAndProjectIdAndIdempotencyKey(Long userId, Long projectId, String idempotencyKey);
    List<CandidateSandboxValidation> findByUserIdAndProjectIdAndArtifactIdOrderByCreatedAtDescIdDesc(Long userId, Long projectId, Long artifactId);
    Optional<CandidateSandboxValidation> findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
            Long userId, Long sessionId, Long artifactId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from CandidateSandboxValidation v where v.validationId=:validationId")
    Optional<CandidateSandboxValidation> lockByValidationId(@Param("validationId") String validationId);
    @Query("select v from CandidateSandboxValidation v where v.status not in ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','CLEANUP_FAILED') and (v.nextAttemptAt is null or v.nextAttemptAt<=current_timestamp)")
    List<CandidateSandboxValidation> findReconcileable();
}
