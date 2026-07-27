package io.paperagent.v2.runtime.bootstrap;

/**
 * A deterministic bootstrap validation failure with an inspectable code and
 * path.
 */
public final class PersistentPlanBootstrapValidationException
        extends IllegalArgumentException {
    private final PersistentPlanBootstrapValidationCode code;
    private final String path;

    PersistentPlanBootstrapValidationException(
            PersistentPlanBootstrapValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public PersistentPlanBootstrapValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
