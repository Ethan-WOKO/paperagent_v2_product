package com.yanban.knowledge.web;

import com.yanban.knowledge.domain.KbDocument;
import java.time.Instant;

public record KbDocumentListItemResponse(
        Long id,
        Long userId,
        String filename,
        String status,
        boolean isPublic,
        String sourceType,
        Long projectId,
        String lineageId,
        Integer versionNo,
        String versionStatus,
        String canonicalKey,
        Instant effectiveAt,
        Instant supersededAt,
        Instant deletedAt,
        String mimeType,
        Long fileSize,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        boolean ownedByCurrentUser,
        boolean administratorPublic,
        boolean downloadAvailable
) {
    public static KbDocumentListItemResponse from(KbDocument document) {
        return from(document, true, false, false);
    }

    public static KbDocumentListItemResponse from(KbDocument document, boolean owned,
                                                  boolean administratorPublic, boolean downloadAvailable) {
        return new KbDocumentListItemResponse(
                document.getId(),
                document.getUserId(),
                document.getFilename(),
                document.getStatus(),
                Boolean.TRUE.equals(document.getIsPublic()),
                document.getSourceType(),
                document.getProjectId(),
                document.getLineageId(),
                document.getVersionNo(),
                document.getVersionStatus(),
                document.getCanonicalKey(),
                document.getEffectiveAt(),
                document.getSupersededAt(),
                document.getDeletedAt(),
                document.getMimeType(),
                document.getFileSize(),
                owned ? document.getErrorMessage() : null,
                document.getCreatedAt(),
                document.getUpdatedAt(),
                owned,
                administratorPublic,
                downloadAvailable
        );
    }
}
