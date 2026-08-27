package com.yanban.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityErrorWriter {

    private final ObjectMapper json;

    public ApiSecurityErrorWriter(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiErrorResponse.of(code, message));
    }
}
