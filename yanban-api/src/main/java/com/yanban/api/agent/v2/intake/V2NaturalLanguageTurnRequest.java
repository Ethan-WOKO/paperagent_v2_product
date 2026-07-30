package com.yanban.api.agent.v2.intake;

import com.yanban.api.agent.AgentExperimentRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record V2NaturalLanguageTurnRequest(
        @NotBlank @Size(max = 20_000) String content,
        Boolean ragDisabled,
        @Size(max = 128) String skillId,
        AgentExperimentRequest experiment,
        @NotBlank @Size(max = 128) String clientRequestId) {
}
