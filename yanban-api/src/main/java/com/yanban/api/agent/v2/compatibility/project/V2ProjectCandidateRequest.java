package com.yanban.api.agent.v2.compatibility.project;

import java.util.List;

public record V2ProjectCandidateRequest(
        String objective, List<String> paths, String clientRequestId) {
}
