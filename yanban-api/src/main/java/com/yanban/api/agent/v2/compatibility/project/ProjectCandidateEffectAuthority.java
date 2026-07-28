package com.yanban.api.agent.v2.compatibility.project;

import java.util.List;

public record ProjectCandidateEffectAuthority(
        String kind,
        String authorityJson,
        String authoritySha256,
        Long userId,
        Long projectId,
        Long sessionId,
        Long turnId,
        String projectVersion,
        String objective,
        List<String> paths) {
    public ProjectCandidateEffectAuthority {
        paths = List.copyOf(paths);
    }
}
