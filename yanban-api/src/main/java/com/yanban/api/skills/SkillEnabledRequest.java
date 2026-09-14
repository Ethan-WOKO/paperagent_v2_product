package com.yanban.api.skills;

import jakarta.validation.constraints.NotNull;

public record SkillEnabledRequest(@NotNull Boolean enabled) {
}
