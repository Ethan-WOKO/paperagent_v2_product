package io.paperagent.v2.runtime.checkpoint;

/**
 * A deterministic initial Checkpoint freeze failure with an inspectable code
 * and path.
 */
public final class InitialCheckpointFreezeValidationException
        extends IllegalArgumentException {
    private final InitialCheckpointFreezeValidationCode code;
    private final String path;

    InitialCheckpointFreezeValidationException(
            InitialCheckpointFreezeValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public InitialCheckpointFreezeValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
