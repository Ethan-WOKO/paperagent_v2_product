package com.yanban.api.agent;

import java.time.Duration;

/** Provider credentials and endpoint metadata for one model call. */
public record ModelInvocationContext(
        String provider,
        String apiKey,
        String apiUrl,
        String traceId,
        Duration timeout,
        boolean thinkingDisabled) {

    public ModelInvocationContext(String provider, String apiKey, String apiUrl, String traceId) {
        this(provider, apiKey, apiUrl, traceId, null, false);
    }

    public ModelInvocationContext(String provider, String apiKey, String apiUrl, String traceId, Duration timeout) {
        this(provider, apiKey, apiUrl, traceId, timeout, false);
    }
}
