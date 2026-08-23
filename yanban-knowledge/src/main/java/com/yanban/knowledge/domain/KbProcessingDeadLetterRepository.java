package com.yanban.knowledge.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbProcessingDeadLetterRepository extends JpaRepository<KbProcessingDeadLetter, Long> {
    Optional<KbProcessingDeadLetter> findByOriginalEventId(String originalEventId);
    List<KbProcessingDeadLetter> findTop100ByStatusOrderByCreatedAtAsc(String status);
    long countByStatus(String status);
}
