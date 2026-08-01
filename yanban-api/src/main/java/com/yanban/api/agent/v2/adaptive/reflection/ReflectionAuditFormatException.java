package com.yanban.api.agent.v2.adaptive.reflection;

/** Raised when the reflection step-state audit cannot satisfy its JSON contract. */
public final class ReflectionAuditFormatException extends RuntimeException {

    public ReflectionAuditFormatException() {
        super("reflection step-state audit returned invalid result");
    }
}
