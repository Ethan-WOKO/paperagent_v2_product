package com.yanban.agent.v2.adapter.provider;

import io.paperagent.v2.providers.GenerationOptions;

import java.util.Map;
import java.util.OptionalLong;

/** Immutable bounded settings for one provider-backed Step turn. */
public record ProductStepTurnConfiguration(
        int maxOutputTokens, double temperature) {
    public ProductStepTurnConfiguration {
        if (maxOutputTokens <= 0 || maxOutputTokens > 32768
                || !Double.isFinite(temperature)
                || temperature < 0.0d || temperature > 2.0d) {
            throw new ProductStepTurnException(
                    ProductStepTurnError.INVALID_CONFIGURATION,
                    "productStepTurn.configuration");
        }
    }

    GenerationOptions generationOptions() {
        return new GenerationOptions(
                maxOutputTokens, 1, temperature, OptionalLong.empty(), Map.of());
    }
}
