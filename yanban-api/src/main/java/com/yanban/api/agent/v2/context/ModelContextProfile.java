package com.yanban.api.agent.v2.context;

public record ModelContextProfile(
        String provider,
        String model,
        long contextWindowTokens,
        long maxOutputTokens,
        String tokenCounterVersion,
        FixedContextBudgetProfile budgetProfile) {

    public ModelContextProfile {
        if (provider == null || provider.isBlank()
                || model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "provider and model are required");
        }
        if (contextWindowTokens <= 0
                || maxOutputTokens <= 0
                || maxOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException(
                    "model token limits are invalid");
        }
        if (tokenCounterVersion == null
                || tokenCounterVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "tokenCounterVersion is required");
        }
        if (budgetProfile == null) {
            throw new IllegalArgumentException(
                    "budgetProfile is required");
        }
    }
}
