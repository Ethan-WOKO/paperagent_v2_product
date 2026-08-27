package com.yanban.api.memory;

import java.time.Instant;

public record MemoryDistillationJobResponse(
        Long id,
        String triggerType,
        String status,
        long fromMessageId,
        long throughMessageId,
        int messageCount,
        int candidateCount,
        int createdMemoryCount,
        int attemptCount,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    static MemoryDistillationJobResponse from(MemoryDistillationJobEntity job) {
        return new MemoryDistillationJobResponse(
                job.id(), job.triggerType(), job.status(), job.fromMessageId(), job.throughMessageId(),
                job.messageCount(), job.candidateCount(), job.createdMemoryCount(), job.attemptCount(),
                job.errorCode(), job.errorMessage(), job.startedAt(), job.finishedAt(),
                job.createdAt(), job.updatedAt());
    }
}
