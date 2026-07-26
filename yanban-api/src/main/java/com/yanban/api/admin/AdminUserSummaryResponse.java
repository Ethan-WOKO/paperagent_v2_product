package com.yanban.api.admin;

import java.time.Instant;

public record AdminUserSummaryResponse(Long id,
                                       String username,
                                       String accountType,
                                       String role,
                                       long aiQuotaTotal,
                                       long aiQuotaUsed,
                                       long aiQuotaRemaining,
                                       Instant createdAt,
                                       Instant lastLoginAt,
                                       long chatSessionCount,
                                       long paperTaskCount,
                                       long projectCount) {
}
