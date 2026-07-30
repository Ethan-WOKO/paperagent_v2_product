package com.yanban.api.agent.v2.adaptive.reflection;

/**
 * Stable fail-closed error for invalid model reflection output.
 */
public final class ReflectionParseException extends RuntimeException {

    public ReflectionParseException() {
        super("reflection response is invalid");
    }
}
