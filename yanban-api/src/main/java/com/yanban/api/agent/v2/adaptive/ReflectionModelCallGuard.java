package com.yanban.api.agent.v2.adaptive;

import io.paperagent.v2.providers.ModelRequest;

/** Context gate immediately before each real Reflection provider call. */
@FunctionalInterface
public interface ReflectionModelCallGuard {
    ModelRequest requireReady(Call call);

    record Call(Long userId, Long turnId, String phase,
                ModelRequest request) {
        public Call {
            if (userId == null || turnId == null || phase == null
                    || phase.isBlank() || request == null) {
                throw new IllegalArgumentException(
                        "reflection model call authority is required");
            }
        }
    }
}
