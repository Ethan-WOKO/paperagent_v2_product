package io.paperagent.v2.chain.model;

public final class ChainProviderProtocolException extends IllegalArgumentException {
    private final ChainProviderProtocolCode code;
    private final String path;

    public ChainProviderProtocolException(ChainProviderProtocolCode code, String path, String message) {
        super(code + " at " + (path == null ? "" : path) + ": " + message);
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.path = path == null ? "" : path;
    }

    public ChainProviderProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
