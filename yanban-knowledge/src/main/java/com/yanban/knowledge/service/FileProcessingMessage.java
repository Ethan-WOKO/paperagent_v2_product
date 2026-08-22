package com.yanban.knowledge.service;

public record FileProcessingMessage(
        String eventId,
        Long documentId,
        Long userId,
        String objectKey,
        String fileDigest
) {
    public FileProcessingMessage(Long documentId, Long userId, String objectKey) {
        this("legacy-" + documentId, documentId, userId, objectKey, null);
    }
}
