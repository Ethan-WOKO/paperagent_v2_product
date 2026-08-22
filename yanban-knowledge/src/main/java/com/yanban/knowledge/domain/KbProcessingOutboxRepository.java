package com.yanban.knowledge.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KbProcessingOutboxRepository extends JpaRepository<KbProcessingOutboxEvent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from KbProcessingOutboxEvent e where e.status in :statuses "
            + "and e.nextAttemptAt <= :now order by e.createdAt asc")
    List<KbProcessingOutboxEvent> lockDue(@Param("statuses") Collection<String> statuses,
                                          @Param("now") Instant now,
                                          Pageable pageable);

    List<KbProcessingOutboxEvent> findByStatusAndUpdatedAtBefore(String status, Instant cutoff);
    boolean existsByDocumentIdAndStatusIn(Long documentId, Collection<String> statuses);
    long countByStatusIn(Collection<String> statuses);
}
