package com.yanban.paper.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PaperSectionRevisionBatchUpdateRequest(
        @NotEmpty List<Long> sectionIds,
        @NotBlank String status
) {
}
