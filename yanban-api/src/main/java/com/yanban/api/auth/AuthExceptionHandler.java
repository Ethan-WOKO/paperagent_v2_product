package com.yanban.api.auth;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    private static final List<String> FIELD_PRIORITY = List.of("username", "password", "inviteCode");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<AuthErrorResponse> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparingInt(this::fieldPriority).thenComparing(FieldError::getField))
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), safeMessage(error)));
        String message = fieldErrors.values().stream().findFirst().orElse("请检查注册信息");
        return ResponseEntity.badRequest().body(new AuthErrorResponse(
                "AUTH_VALIDATION_FAILED", message, fieldErrors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AuthErrorResponse> responseStatus(ResponseStatusException exception) {
        String message = exception.getReason() == null || exception.getReason().isBlank()
                ? "认证请求失败"
                : exception.getReason();
        String code = codeFor(exception.getStatusCode().value(), message);
        Map<String, String> fieldErrors = code.startsWith("INVITE_CODE_")
                ? Map.of("inviteCode", message)
                : Map.of();
        return ResponseEntity.status(exception.getStatusCode())
                .body(new AuthErrorResponse(code, message, fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<AuthErrorResponse> unreadableBody() {
        return ResponseEntity.badRequest().body(new AuthErrorResponse(
                "AUTH_REQUEST_INVALID", "注册信息格式不正确", Map.of()));
    }

    private int fieldPriority(FieldError error) {
        int index = FIELD_PRIORITY.indexOf(error.getField());
        return index < 0 ? FIELD_PRIORITY.size() : index;
    }

    private String safeMessage(FieldError error) {
        return error.getDefaultMessage() == null || error.getDefaultMessage().isBlank()
                ? "字段内容不正确"
                : error.getDefaultMessage();
    }

    private String codeFor(int status, String message) {
        if (message.contains("用户名已存在")) return "USERNAME_TAKEN";
        if (message.contains("请填写邀请码")) return "INVITE_CODE_REQUIRED";
        if (message.contains("邀请码无效")) return "INVITE_CODE_INVALID";
        if (message.contains("邀请码已停用")) return "INVITE_CODE_DISABLED";
        if (message.contains("邀请码使用次数已达上限")) return "INVITE_CODE_EXHAUSTED";
        if (message.contains("用户名或密码错误")) return "INVALID_CREDENTIALS";
        if (status == HttpStatus.UNAUTHORIZED.value()) return "AUTH_UNAUTHORIZED";
        return "AUTH_REQUEST_FAILED";
    }
}
