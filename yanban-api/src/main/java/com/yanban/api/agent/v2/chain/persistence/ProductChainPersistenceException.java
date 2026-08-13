package com.yanban.api.agent.v2.chain.persistence;

/** Stable product-side failure for an authoritative chain persistence boundary. */
public final class ProductChainPersistenceException extends RuntimeException {
    private final String code;

    ProductChainPersistenceException(String code) {
        super(code);
        this.code = code;
    }

    ProductChainPersistenceException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
