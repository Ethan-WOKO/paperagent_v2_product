package com.yanban.api.agent.v2.chain.api;

import org.springframework.http.HttpStatus;

public final class ProjectChainApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ProjectChainApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
