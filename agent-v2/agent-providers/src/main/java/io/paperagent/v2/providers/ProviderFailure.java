package io.paperagent.v2.providers;

import java.util.Map;

public record ProviderFailure(
        ProviderFailureCode code,
        String message,
        Map<String, String> details) implements ModelProviderResult {

    public ProviderFailure {
        ProviderValues.required(code, "providerFailure.code");
        message = ProviderValues.text(message, "providerFailure.message");
        details = ProviderValues.map(details, "providerFailure.details");
        details.forEach((key, value) -> {
            ProviderValues.text(key, "providerFailure.details.key");
            ProviderValues.text(value, "providerFailure.details.value");
        });
    }
}
