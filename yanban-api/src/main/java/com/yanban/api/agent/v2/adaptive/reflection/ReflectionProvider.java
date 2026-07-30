package com.yanban.api.agent.v2.adaptive.reflection;

/**
 * Provider-neutral boundary for obtaining one reflection decision.
 *
 * <p>The provider returns its untrusted model output. Callers must parse it
 * with {@link StrictReflectionDecisionParser} before acting on it.</p>
 */
@FunctionalInterface
public interface ReflectionProvider {

    String reflect(ReflectionContext context);
}
