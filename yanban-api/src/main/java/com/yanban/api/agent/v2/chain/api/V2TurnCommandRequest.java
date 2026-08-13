package com.yanban.api.agent.v2.chain.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record V2TurnCommandRequest(
        @NotBlank @Size(max = 20_000) String content,
        @NotBlank @Size(max = 128) String clientRequestId) {
}
