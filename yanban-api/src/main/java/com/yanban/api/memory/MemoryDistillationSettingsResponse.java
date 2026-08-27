package com.yanban.api.memory;

import java.time.Instant;

public record MemoryDistillationSettingsResponse(
        boolean available,
        boolean autoEnabled,
        long intervalSeconds,
        long lastProcessedMessageId,
        Instant nextRunAt,
        Instant lastSuccessAt,
        MemoryDistillationJobResponse latestJob
) { }
