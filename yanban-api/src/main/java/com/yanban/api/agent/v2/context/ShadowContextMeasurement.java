package com.yanban.api.agent.v2.context;

import java.util.List;

public record ShadowContextMeasurement(
        String profileVersion,
        String tokenCounterVersion,
        long contextWindowTokens,
        long maxOutputTokens,
        long estimatedInputTokens,
        List<ContextSectionUsage> sections) {

    public ShadowContextMeasurement {
        sections = List.copyOf(sections);
    }

    public long overLimitSectionCount() {
        return sections.stream().filter(ContextSectionUsage::overLimit).count();
    }

    public ContextSectionUsage section(ContextSectionType type) {
        return sections.stream()
                .filter(value -> value.section() == type)
                .findFirst()
                .orElseThrow();
    }
}
