package com.yanban.paper.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LiteratureSearchTaskRepository extends JpaRepository<LiteratureSearchTask, Long> {
    Optional<LiteratureSearchTask> findByIdAndUserId(Long id, Long userId);

    Optional<LiteratureSearchTask> findByIdempotencyKey(String idempotencyKey);

    List<LiteratureSearchTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<LiteratureSearchTask> findByCreatedAtAfterOrderByCreatedAtDesc(Instant createdAt);

    List<LiteratureSearchTask> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(String status,
                                                                                 Instant updatedBefore,
                                                                                 Pageable pageable);

    List<LiteratureSearchTask> findByStatusAndStartedAtBeforeOrderByStartedAtAsc(String status,
                                                                                 Instant startedBefore,
                                                                                 Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LiteratureSearchTask task
               set task.status = :status,
                   task.currentStage = :stage,
                   task.cancelReason = :cancelReason,
                   task.updatedAt = :updatedAt
             where task.id = :taskId
               and task.userId = :userId
               and task.status in :activeStatuses
            """)
    int requestCancelIfActive(
            @Param("taskId") Long taskId,
            @Param("userId") Long userId,
            @Param("activeStatuses") Set<String> activeStatuses,
            @Param("status") String status,
            @Param("stage") String stage,
            @Param("cancelReason") String cancelReason,
            @Param("updatedAt") Instant updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LiteratureSearchTask task
               set task.resultJson = :resultJson,
                   task.rawCandidateCount = :rawCandidateCount,
                   task.uniqueCandidateCount = :uniqueCandidateCount,
                   task.sourceAttempts = :sourceAttempts,
                   task.sourceFailuresJson = :sourceFailuresJson,
                   task.status = :status,
                   task.currentStage = :stage,
                   task.finishedAt = :finishedAt,
                   task.updatedAt = :finishedAt
             where task.id = :taskId
               and task.userId = :userId
               and task.status in :activeStatuses
            """)
    int completeIfActive(
            @Param("taskId") Long taskId,
            @Param("userId") Long userId,
            @Param("activeStatuses") Set<String> activeStatuses,
            @Param("resultJson") String resultJson,
            @Param("rawCandidateCount") Integer rawCandidateCount,
            @Param("uniqueCandidateCount") Integer uniqueCandidateCount,
            @Param("sourceAttempts") Integer sourceAttempts,
            @Param("sourceFailuresJson") String sourceFailuresJson,
            @Param("status") String status,
            @Param("stage") String stage,
            @Param("finishedAt") Instant finishedAt);
}
