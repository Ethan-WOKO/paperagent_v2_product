package com.yanban.api.agent.reactplan.gateway;

import org.springframework.http.HttpStatus;

public final class EngineGatewayException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    EngineGatewayException(HttpStatus status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static EngineGatewayException unauthorized(String code) {
        return new EngineGatewayException(HttpStatus.UNAUTHORIZED, code);
    }

    static EngineGatewayException forbidden(String code) {
        return new EngineGatewayException(HttpStatus.FORBIDDEN, code);
    }

    static EngineGatewayException badRequest(String code) {
        return new EngineGatewayException(HttpStatus.BAD_REQUEST, code);
    }

    static EngineGatewayException notFound(String code) {
        return new EngineGatewayException(HttpStatus.NOT_FOUND, code);
    }

    static EngineGatewayException conflict(String code) {
        return new EngineGatewayException(HttpStatus.CONFLICT, code);
    }

    static EngineGatewayException tooLarge(String code) {
        return new EngineGatewayException(HttpStatus.PAYLOAD_TOO_LARGE, code);
    }

    static EngineGatewayException tooManyRequests(String code) {
        return new EngineGatewayException(HttpStatus.TOO_MANY_REQUESTS, code);
    }

    static EngineGatewayException badGateway(String code) {
        return new EngineGatewayException(HttpStatus.BAD_GATEWAY, code);
    }
}
