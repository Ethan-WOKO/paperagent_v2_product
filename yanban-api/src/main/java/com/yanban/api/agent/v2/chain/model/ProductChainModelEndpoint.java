package com.yanban.api.agent.v2.chain.model;

/**
 * Transient endpoint selected for one chain model attempt.
 *
 * <p>Credentials are deliberately excluded from {@link #toString()} so this
 * object remains safe when included in diagnostic object graphs.</p>
 */
public record ProductChainModelEndpoint(
        String provider,
        String model,
        String apiKey,
        String apiUrl) {
    public ProductChainModelEndpoint {
        provider = required(provider, "provider");
        model = required(model, "model");
    }

    @Override
    public String toString() {
        return "ProductChainModelEndpoint[provider=" + provider
                + ", model=" + model + ", credentials=redacted]";
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
