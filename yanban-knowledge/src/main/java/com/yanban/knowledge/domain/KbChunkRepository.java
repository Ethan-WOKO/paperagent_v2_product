package com.yanban.knowledge.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {
    @Query(value = """
            select c.*
            from kb_chunks c
            join kb_documents d on d.id = c.document_id
            where d.status = 'READY'
              and d.version_status = 'ACTIVE'
              and (d.user_id = :userId or d.is_public = true)
              and lower(c.chunk_text) like lower(concat('%', :query, '%'))
            order by c.id asc
            """, nativeQuery = true)
    List<KbChunk> searchAccessibleChunks(@Param("query") String query, @Param("userId") Long userId, Pageable pageable);

    @Query(value = """
            select c.*
            from kb_chunks c
            join kb_documents d on d.id = c.document_id
            where d.status = 'READY'
              and (
                d.version_status = 'ACTIVE'
                or (:includeSuperseded = true and d.version_status = 'SUPERSEDED')
              )
              and d.version_status not in ('DELETED', 'ARCHIVED')
              and (d.user_id = :userId or d.is_public = true)
              and (:projectId is null or d.project_id is null or d.project_id = :projectId)
              and lower(c.chunk_text) like lower(concat('%', :query, '%'))
            order by c.id asc
            """, nativeQuery = true)
    List<KbChunk> searchAccessibleVersionedChunks(@Param("query") String query,
                                                  @Param("userId") Long userId,
                                                  @Param("projectId") Long projectId,
                                                  @Param("includeSuperseded") boolean includeSuperseded,
                                                  Pageable pageable);

    List<KbChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    List<KbChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId, Pageable pageable);

    int countByDocumentId(Long documentId);

    @Transactional
    void deleteByDocumentId(Long documentId);
}
