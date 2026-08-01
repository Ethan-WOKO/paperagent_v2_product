package com.yanban.api.agent;

/** Provider credentials and endpoint metadata for one model call. */
public record ModelInvocationContext(
        String provider,
        String apiKey,
        String apiUrl,
        String traceId) {
}
