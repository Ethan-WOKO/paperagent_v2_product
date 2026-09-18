package com.yanban.api.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserModelRequest(
        @NotBlank
        @Size(max = 128)
        String label,

        @NotBlank
        @Size(max = 512)
        String apiUrl,

        @Size(max = 255)
        String apiKey,

        @NotBlank
        @Size(max = 128)
        String modelName,
        Boolean supportsVision
) {
    public UserModelRequest(String label, String apiUrl, String apiKey, String modelName) {
        this(label, apiUrl, apiKey, modelName, null);
    }
}
