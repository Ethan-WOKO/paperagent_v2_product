package com.yanban.api.agent.v2.adaptive;

import java.util.List;

public record V2AdaptiveTurnResponse(
        String status,
        String route,
        String planId,
        String projectVersion,
        List<Step> steps,
        String finalText,
        String candidateArtifactId,
        List<String> outputPaths,
        String errorCode) {
    public V2AdaptiveTurnResponse {
        steps = List.copyOf(steps);
        outputPaths = List.copyOf(outputPaths);
    }

    public record Step(int index, String title, String status, String detail) {
    }
}
