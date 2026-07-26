package com.yanban.api.user;

public record UserMeResponse(Long id,
                             String username,
                             String accountType,
                             boolean demo,
                             String role,
                             long aiQuotaTotal,
                             long aiQuotaUsed,
                             long aiQuotaRemaining) {
}
