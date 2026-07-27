package io.paperagent.v2.runtime.planning;

/**
 * A deterministic initial Plan freeze failure with an inspectable code and
 * path.
 */
public final class InitialPlanFreezeValidationException extends IllegalArgumentException {
    private final InitialPlanFreezeValidationCode code;
    private final String path;

    InitialPlanFreezeValidationException(
            InitialPlanFreezeValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public InitialPlanFreezeValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
