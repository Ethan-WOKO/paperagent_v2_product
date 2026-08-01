package com.yanban.api.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAgentPlanRequest(
        @NotBlank @Size(max = 20000) String content,
        Boolean ragDisabled,
        String skillId,
        Boolean autoExecute
) {
}
