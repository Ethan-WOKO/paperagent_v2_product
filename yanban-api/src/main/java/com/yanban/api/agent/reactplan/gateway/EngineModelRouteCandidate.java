package com.yanban.api.agent.reactplan.gateway;

public record EngineModelRouteCandidate(String provider, String model) {
    public EngineModelRouteCandidate {
        if (provider == null || provider.isBlank() || provider.length() > 64
                || model == null || model.isBlank() || model.length() > 128) {
            throw new IllegalArgumentException("engine model route candidate is invalid");
        }
    }
}
