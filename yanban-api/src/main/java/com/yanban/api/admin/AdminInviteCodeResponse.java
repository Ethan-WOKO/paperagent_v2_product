package com.yanban.api.admin;

import java.time.Instant;

public record AdminInviteCodeResponse(Long id,
                                      String code,
                                      int maxUses,
                                      int usedCount,
                                      boolean enabled,
                                      Instant createdAt) {
}
