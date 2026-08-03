package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.providers.ModelRequest;

/** Product gate used only immediately before a real Step model call. */
@FunctionalInterface
public interface StepModelCallGuard {
    ModelRequest requireReady(Call call);

    record Call(Long userId, Long turnId,
                String activationEventId, long activationSequence,
                long activatedCheckpointVersion,
                V2StepModelCallMaterial material) {
        public Call {
            if (userId == null || turnId == null
                    || activationEventId == null || activationEventId.isBlank()
                    || activationSequence < 1 || activatedCheckpointVersion < 1
                    || material == null) {
                throw new IllegalArgumentException("step model call authority is required");
            }
        }
    }
}
