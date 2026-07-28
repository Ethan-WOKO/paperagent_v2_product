package com.yanban.api.agent.v2.compatibility.project;

import java.util.List;

public record V2ProjectAnalysisRequest(
        String objective,
        List<String> paths,
        String searchQuery,
        Integer maxSearchResults,
        String clientRequestId) {
}
