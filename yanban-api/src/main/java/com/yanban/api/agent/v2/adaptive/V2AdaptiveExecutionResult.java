package com.yanban.api.agent.v2.adaptive;

import java.util.List;

public record V2AdaptiveExecutionResult(
        String status,
        List<V2AdaptiveTurnResponse.Step> steps,
        String finalText,
        String errorCode,
        int reflections,
        int replans,
        int repairs,
        Long candidateArtifactId,
        List<String> outputPaths) {
    public V2AdaptiveExecutionResult {
        steps = List.copyOf(steps);
        outputPaths = List.copyOf(outputPaths);
    }

    public V2AdaptiveExecutionResult(
            String status, List<V2AdaptiveTurnResponse.Step> steps,
            String finalText, String errorCode,
            int reflections, int replans, int repairs) {
        this(status, steps, finalText, errorCode,
                reflections, replans, repairs, null, List.of());
    }

    public V2AdaptiveExecutionResult(
            String status, List<V2AdaptiveTurnResponse.Step> steps,
            String finalText, String errorCode,
            int reflections, int replans, int repairs,
            Long candidateArtifactId) {
        this(status, steps, finalText, errorCode,
                reflections, replans, repairs,
                candidateArtifactId, List.of());
    }
}
