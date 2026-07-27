package io.paperagent.v2.contracts;

/**
 * The complete top-level routing decision. Persistent execution semantics are
 * intentionally absent from DIRECT.
 */
public enum Route {
    DIRECT,
    PERSISTENT_PLAN_EXECUTE
}
