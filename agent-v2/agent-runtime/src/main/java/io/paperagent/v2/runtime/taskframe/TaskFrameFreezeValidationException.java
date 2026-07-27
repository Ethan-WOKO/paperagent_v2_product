package io.paperagent.v2.runtime.taskframe;

/**
 * A deterministic structural freeze failure with an inspectable code and path.
 */
public final class TaskFrameFreezeValidationException extends IllegalArgumentException {
    private final TaskFrameFreezeValidationCode code;
    private final String path;

    TaskFrameFreezeValidationException(
            TaskFrameFreezeValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public TaskFrameFreezeValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
