package com.yanban.api.agent.v2.context;

public record ContextSectionUsage(
        ContextSectionType section,
        long tokenLimit,
        long estimatedTokens,
        boolean overLimit) {
}
