package io.paperagent.v2.runtime.routing;

/**
 * A deterministic routing validation failure whose code and field path are
 * inspectable without parsing exception text.
 */
public final class RoutingValidationException extends IllegalArgumentException {
    private final RoutingValidationCode code;
    private final String path;

    RoutingValidationException(
            RoutingValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public RoutingValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
