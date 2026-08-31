package com.yanban.api.memory;

import java.math.BigDecimal;
import java.util.List;

record MemoryDistillationCandidate(
        String scope,
        Long projectId,
        String memoryType,
        String content,
        List<String> tags,
        BigDecimal confidence,
        List<Long> sourceMessageIds
) {
    MemoryDistillationCandidate {
        tags = tags == null ? List.of() : List.copyOf(tags);
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
    }
}
