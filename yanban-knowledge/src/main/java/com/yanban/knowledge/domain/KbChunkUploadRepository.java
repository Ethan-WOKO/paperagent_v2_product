package com.yanban.knowledge.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbChunkUploadRepository extends JpaRepository<KbChunkUpload, Long> {
    Optional<KbChunkUpload> findByUploadIdAndChunkNumber(String uploadId, Integer chunkNumber);

    Optional<KbChunkUpload> findByUserIdAndUploadIdAndChunkNumber(Long userId, String uploadId, Integer chunkNumber);

    List<KbChunkUpload> findByUploadIdOrderByChunkNumberAsc(String uploadId);

    List<KbChunkUpload> findByUserIdAndUploadIdOrderByChunkNumberAsc(Long userId, String uploadId);

    List<KbChunkUpload> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
