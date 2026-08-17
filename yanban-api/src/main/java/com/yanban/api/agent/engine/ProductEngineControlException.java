package com.yanban.api.agent.engine;

final class ProductEngineControlException extends RuntimeException {
    private final int status;
    private final String code;

    ProductEngineControlException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    int status() { return status; }
    String code() { return code; }
}
