package com.yanban.api.agent.v2.compatibility.literature;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record V2LiteratureTurnRequest(
        @NotBlank @Size(max = 1000) String query,
        Integer topK,
        Integer yearFrom,
        Boolean includeBibtex,
        @NotBlank @Size(max = 128) String clientRequestId) {
}
