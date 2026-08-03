package com.yanban.api.agent.v2.adaptive;

import java.util.List;

public record V2AdaptiveTurnResponse(
        String status,
        String route,
        String planId,
        String projectVersion,
        List<Step> steps,
        String finalText,
        Long candidateArtifactId,
        List<String> outputPaths,
        String errorCode,
        Context context) {
    public V2AdaptiveTurnResponse(
            String status, String route, String planId,
            String projectVersion, List<Step> steps, String finalText,
            Long candidateArtifactId, List<String> outputPaths,
            String errorCode) {
        this(status, route, planId, projectVersion, steps, finalText,
                candidateArtifactId, outputPaths, errorCode, null);
    }

    public V2AdaptiveTurnResponse {
        steps = List.copyOf(steps);
        outputPaths = List.copyOf(outputPaths);
    }

    public V2AdaptiveTurnResponse withContext(Context value) {
        return new V2AdaptiveTurnResponse(
                status, route, planId, projectVersion, steps, finalText,
                candidateArtifactId, outputPaths, errorCode, value);
    }

    public record Step(int index, String title, String status, String detail) {
    }

    public record Context(
            String phase,
            String stepId,
            List<String> compactedSections) {
        public Context {
            compactedSections = compactedSections == null
                    ? List.of() : List.copyOf(compactedSections);
        }
    }
}
