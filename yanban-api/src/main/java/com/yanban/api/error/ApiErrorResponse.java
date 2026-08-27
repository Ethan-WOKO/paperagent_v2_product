package com.yanban.api.error;

import java.util.Map;

/** Stable error envelope returned by every product HTTP API. */
public record ApiErrorResponse(String code, String message, Map<String, String> fieldErrors) {

    public ApiErrorResponse {
        code = code == null || code.isBlank() ? "REQUEST_FAILED" : code;
        message = message == null || message.isBlank() ? "请求失败" : message;
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, Map.of());
    }
}
