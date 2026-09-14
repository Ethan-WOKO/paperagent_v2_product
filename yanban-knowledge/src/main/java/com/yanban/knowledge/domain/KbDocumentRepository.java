package com.yanban.knowledge.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
    @org.springframework.data.jpa.repository.Query("""
            select document from KbDocument document
            where document.userId in :publisherIds and document.isPublic = true
              and upper(document.status) = 'READY' and document.deletedAt is null
              and upper(coalesce(document.versionStatus, 'ACTIVE')) = 'ACTIVE'
            """)
    java.util.List<KbDocument> findPublicDocumentsByPublisherIds(
            @org.springframework.data.repository.query.Param("publisherIds") java.util.Set<Long> publisherIds);

    Optional<KbDocument> findByIdAndUserId(Long id, Long userId);

    Optional<KbDocument> findByUserIdAndUploadId(Long userId, String uploadId);

    java.util.List<KbDocument> findByStatusIn(java.util.Collection<String> statuses, org.springframework.data.domain.Pageable pageable);

    java.util.List<KbDocument> findByUserIdOrderByCreatedAtDesc(Long userId);

    java.util.List<KbDocument> findByUserIdAndSourceType(Long userId, String sourceType);

    long countByUserIdAndSourceType(Long userId, String sourceType);
}
