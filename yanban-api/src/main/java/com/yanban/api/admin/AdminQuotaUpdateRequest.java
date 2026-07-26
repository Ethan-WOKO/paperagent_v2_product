package com.yanban.api.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminQuotaUpdateRequest(@NotNull @Min(-1) Long totalQuota,
                                      boolean resetUsed) {
}
