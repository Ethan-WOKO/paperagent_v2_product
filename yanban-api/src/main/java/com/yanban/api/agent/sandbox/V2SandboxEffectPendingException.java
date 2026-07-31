package com.yanban.api.agent.sandbox;

/**
 * The shared broker accepted or still owns an execution whose terminal
 * receipt is not known yet. No V2 EffectOutcome may be invented for it.
 */
public final class V2SandboxEffectPendingException
        extends IllegalStateException {
    public V2SandboxEffectPendingException() {
        super("V2 sandbox execution remains pending");
    }
}
