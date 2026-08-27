package com.yanban.api.error;

import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/** Business failure with an explicit stable API code. */
public final class ApiException extends ResponseStatusException {

    private final String code;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatusCode status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatusCode status,
                        String code,
                        String message,
                        Map<String, String> fieldErrors) {
        super(status, message);
        this.code = code;
        this.fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
