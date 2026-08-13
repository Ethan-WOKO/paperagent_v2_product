package com.yanban.api.agent.v2.intake;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record V2NaturalLanguageTurnRequest(
        @NotBlank @Size(max = 20_000) String content,
        Boolean ragDisabled,
        @Size(max = 128) String skillId,
        @NotBlank @Size(max = 128) String clientRequestId,
        String instructionKind,
        @Size(max = 128) String targetClientRequestId) {
}
