package com.yanban.api.memory;

import jakarta.validation.constraints.NotNull;

public record UpdateMemoryDistillationSettingsRequest(@NotNull Boolean autoEnabled) { }
