package io.paperagent.v2.providers;

/**
 * A deterministic validation failure that callers can inspect without parsing
 * exception text.
 */
public final class ProviderValidationException extends IllegalArgumentException {
    private final ProviderValidationCode code;
    private final String path;

    public ProviderValidationException(
            ProviderValidationCode code,
            String path,
            String message) {
        super(message);
        if (code == null) {
            throw new IllegalArgumentException("code is required");
        }
        this.code = code;
        this.path = path == null ? "" : path;
    }

    public ProviderValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
