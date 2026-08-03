package com.yanban.api.agent.v2.context;

public record ContextSectionBudget(
        ContextSectionType section,
        int percentage,
        long tokenLimit) {

    public ContextSectionBudget {
        if (section == null) {
            throw new IllegalArgumentException("section is required");
        }
        if (percentage <= 0 || percentage > 100) {
            throw new IllegalArgumentException(
                    "percentage must be between 1 and 100");
        }
        if (tokenLimit < 0) {
            throw new IllegalArgumentException(
                    "tokenLimit must not be negative");
        }
    }
}
