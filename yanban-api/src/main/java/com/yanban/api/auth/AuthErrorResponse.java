package com.yanban.api.auth;

import java.util.Map;

public record AuthErrorResponse(String code, String message, Map<String, String> fieldErrors) {
    public AuthErrorResponse {
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }
}
