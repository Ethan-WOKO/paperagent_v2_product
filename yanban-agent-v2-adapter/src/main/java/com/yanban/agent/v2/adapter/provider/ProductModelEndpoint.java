package com.yanban.agent.v2.adapter.provider;

/**
 * Transient product model endpoint. Credentials are intentionally excluded
 * from its string representation.
 */
public final class ProductModelEndpoint {
    private final String provider;
    private final String model;
    private final String apiKey;
    private final String apiUrl;

    public ProductModelEndpoint(
            String provider, String model, String apiKey, String apiUrl) {
        this.provider = required(provider, "endpoint.provider");
        this.model = required(model, "endpoint.model");
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public String apiKey() {
        return apiKey;
    }

    public String apiUrl() {
        return apiUrl;
    }

    @Override
    public String toString() {
        return "ProductModelEndpoint[redacted]";
    }

    private static String required(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new ProductStepTurnException(
                    ProductStepTurnError.INVALID_CONFIGURATION, path);
        }
        return value.trim();
    }
}
