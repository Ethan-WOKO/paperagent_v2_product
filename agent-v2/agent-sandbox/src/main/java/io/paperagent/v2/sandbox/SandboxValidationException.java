package io.paperagent.v2.sandbox;

/**
 * A deterministic validation failure whose code and path are inspectable.
 */
public final class SandboxValidationException extends IllegalArgumentException {
    private final SandboxValidationCode code;
    private final String path;

    public SandboxValidationException(
            SandboxValidationCode code,
            String path,
            String message) {
        super(message);
        if (code == null) {
            throw new IllegalArgumentException("code is required");
        }
        this.code = code;
        this.path = path == null ? "" : path;
    }

    public SandboxValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
