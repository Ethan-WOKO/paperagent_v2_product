package com.yanban.api.admin;

import java.time.Instant;

public record AdminInviteCodeResponse(Long id,
                                      String code,
                                      int maxUses,
                                      int usedCount,
                                      int remainingUses,
                                      boolean enabled,
                                      String status,
                                      Instant createdAt) {
}
