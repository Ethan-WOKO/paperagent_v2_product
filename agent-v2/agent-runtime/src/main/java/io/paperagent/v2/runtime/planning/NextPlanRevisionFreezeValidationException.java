package io.paperagent.v2.runtime.planning;

/**
 * A deterministic next-revision freeze failure with an inspectable code and
 * path.
 */
public final class NextPlanRevisionFreezeValidationException extends IllegalArgumentException {
    private final NextPlanRevisionFreezeValidationCode code;
    private final String path;

    NextPlanRevisionFreezeValidationException(
            NextPlanRevisionFreezeValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public NextPlanRevisionFreezeValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
