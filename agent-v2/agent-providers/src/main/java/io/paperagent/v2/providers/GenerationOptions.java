package io.paperagent.v2.providers;

import java.util.Map;
import java.util.OptionalLong;

public record GenerationOptions(
        int maxOutputTokens,
        int maxProposedToolCalls,
        double temperature,
        OptionalLong seed,
        Map<String, String> deterministicOptions) {

    public GenerationOptions {
        if (maxOutputTokens <= 0) {
            ProviderValues.fail(
                    ProviderValidationCode.INVALID_GENERATION_OPTIONS,
                    "generationOptions.maxOutputTokens",
                    "maxOutputTokens must be positive");
        }
        if (maxProposedToolCalls < 0) {
            ProviderValues.fail(
                    ProviderValidationCode.INVALID_GENERATION_OPTIONS,
                    "generationOptions.maxProposedToolCalls",
                    "maxProposedToolCalls must be non-negative");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0d || temperature > 2.0d) {
            ProviderValues.fail(
                    ProviderValidationCode.INVALID_GENERATION_OPTIONS,
                    "generationOptions.temperature",
                    "temperature must be finite and between 0 and 2");
        }
        seed = ProviderValues.required(seed, "generationOptions.seed");
        deterministicOptions = ProviderValues.map(
                deterministicOptions,
                "generationOptions.deterministicOptions");
        deterministicOptions.forEach((key, value) -> {
            ProviderValues.text(key, "generationOptions.deterministicOptions.key");
            ProviderValues.text(value, "generationOptions.deterministicOptions.value");
        });
    }
}
