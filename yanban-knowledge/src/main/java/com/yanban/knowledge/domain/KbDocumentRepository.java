package com.yanban.knowledge.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
    Optional<KbDocument> findByIdAndUserId(Long id, Long userId);

    Optional<KbDocument> findByUserIdAndUploadId(Long userId, String uploadId);

    java.util.List<KbDocument> findByStatusIn(java.util.Collection<String> statuses, org.springframework.data.domain.Pageable pageable);

    java.util.List<KbDocument> findByUserIdOrderByCreatedAtDesc(Long userId);

    java.util.List<KbDocument> findByUserIdAndSourceType(Long userId, String sourceType);

    long countByUserIdAndSourceType(Long userId, String sourceType);
}
