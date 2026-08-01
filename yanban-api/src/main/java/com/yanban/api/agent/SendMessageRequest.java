package com.yanban.api.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank @Size(max = 20000) String content,
        Boolean ragDisabled,
        String skillId,
        String clientRequestId,
        AgentExperimentRequest experiment
) {
}
