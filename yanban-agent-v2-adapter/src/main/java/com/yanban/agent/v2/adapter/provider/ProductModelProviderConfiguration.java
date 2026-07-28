package com.yanban.agent.v2.adapter.provider;

/** Immutable, credential-free product model selection. */
public record ProductModelProviderConfiguration(String provider, String model) {
    public ProductModelProviderConfiguration {
        provider = required(provider, "provider");
        model = required(model, "model");
    }

    private static String required(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new ProductStepTurnException(
                    ProductStepTurnError.INVALID_CONFIGURATION, path);
        }
        return value.trim();
    }
}
