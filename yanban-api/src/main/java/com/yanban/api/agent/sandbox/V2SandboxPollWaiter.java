package com.yanban.api.agent.sandbox;

import org.springframework.stereotype.Component;

/**
 * Bounded same-request wait between broker status reads. The outer adaptive
 * cycle may safely redispatch the same durable idempotency key after this
 * budget is exhausted.
 */
@Component
public class V2SandboxPollWaiter {
    public int maximumPolls() {
        return 10;
    }

    public void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new V2SandboxEffectPendingException();
        }
    }
}
