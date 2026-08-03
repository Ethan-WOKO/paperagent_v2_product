package com.yanban.api.agent.v2.adaptive;

import io.paperagent.v2.providers.ModelRequest;

/** Fail-closed context boundary immediately before final provider execution. */
@FunctionalInterface
public interface FinalSynthesisModelCallGuard {
    ModelRequest requireReady(Call call);

    record Call(Long userId, Long turnId, ModelRequest request) {
        public Call {
            if (userId == null || turnId == null || request == null) {
                throw new IllegalArgumentException(
                        "final synthesis call authority is required");
            }
        }
    }
}
