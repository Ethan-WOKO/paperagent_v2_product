package com.yanban.agent.v2.adapter.provider;

/** Bounded adapter failure that never includes model or prompt payloads. */
public final class ProductStepTurnException extends RuntimeException {
    private final ProductStepTurnError code;
    private final String path;

    public ProductStepTurnException(ProductStepTurnError code, String path) {
        super(message(code, path));
        this.code = require(code);
        this.path = require(path);
    }

    public ProductStepTurnError code() {
        return code;
    }

    public String path() {
        return path;
    }

    @Override
    public String toString() {
        return "ProductStepTurnException[code=" + code + ", path=" + path + "]";
    }

    private static String message(ProductStepTurnError code, String path) {
        return "product Step turn failed at "
                + (path == null ? "<unknown>" : path)
                + " (" + (code == null ? "<unknown>" : code) + ")";
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw new IllegalArgumentException("failure metadata is required");
        }
        return value;
    }
}
