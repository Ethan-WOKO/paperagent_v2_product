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
        // The local E2B provider may spend more than forty seconds creating a
        // fresh sandbox before the first terminal status is available. Keep
        // the bounded request wait above that startup window while retaining
        // the outer recovery path for genuinely slow executions.
        return 180;
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
