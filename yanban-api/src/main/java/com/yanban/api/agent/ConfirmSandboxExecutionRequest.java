package com.yanban.api.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConfirmSandboxExecutionRequest(
        @NotBlank
        @Size(min = 16, max = 128)
        @Pattern(regexp = "[A-Za-z0-9._:-]+")
        String idempotencyKey
) { }
