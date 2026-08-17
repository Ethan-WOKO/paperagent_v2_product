package com.yanban.api.memory;

import java.util.List;

public record LongTermMemorySnapshot(List<Entry> entries) {

    public LongTermMemorySnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static LongTermMemorySnapshot empty() {
        return new LongTermMemorySnapshot(List.of());
    }

    public record Entry(
            String id,
            String scope,
            String memoryType,
            String content,
            String updatedAt) { }
}
