package com.yanban.api.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameProjectRequest(
        @NotBlank @Size(max = 255) String name
) {
}
