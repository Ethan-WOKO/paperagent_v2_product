package com.yanban.api.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final List<String> FIELD_PRIORITY = List.of("username", "password", "inviteCode");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> api(ApiException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ApiErrorResponse(exception.code(), reason(exception), exception.fieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception,
                                                HttpServletRequest request) {
        Map<String, String> fieldErrors = fieldErrors(exception.getBindingResult().getFieldErrors());
        String message = fieldErrors.values().stream().findFirst().orElse("请检查请求信息");
        String code = request.getRequestURI().startsWith("/api/v1/auth/")
                ? "AUTH_VALIDATION_FAILED" : "VALIDATION_FAILED";
        return ResponseEntity.badRequest().body(new ApiErrorResponse(code, message, fieldErrors));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiErrorResponse> binding(BindException exception) {
        Map<String, String> fieldErrors = fieldErrors(exception.getBindingResult().getFieldErrors());
        String message = fieldErrors.values().stream().findFirst().orElse("请检查请求参数");
        return ResponseEntity.badRequest().body(new ApiErrorResponse("VALIDATION_FAILED", message, fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> constraint(ConstraintViolationException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(value -> value.getPropertyPath().toString()))
                .forEach(value -> fields.putIfAbsent(
                        value.getPropertyPath().toString(), value.getMessage()));
        String message = fields.values().stream().findFirst().orElse("请检查请求参数");
        return ResponseEntity.badRequest().body(new ApiErrorResponse("VALIDATION_FAILED", message, fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody() {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                "INVALID_REQUEST_BODY", "请求信息格式不正确"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingParameter(MissingServletRequestParameterException exception) {
        String message = "缺少请求参数：" + exception.getParameterName();
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                "MISSING_REQUEST_PARAMETER", message,
                Map.of(exception.getParameterName(), message)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException exception) {
        String message = "请求参数格式不正确：" + exception.getName();
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                "INVALID_REQUEST_PARAMETER", message, Map.of(exception.getName(), message)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiErrorResponse.of("METHOD_NOT_ALLOWED", "请求方法不受支持"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> mediaTypeNotSupported() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiErrorResponse.of("UNSUPPORTED_MEDIA_TYPE", "请求内容类型不受支持"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiErrorResponse.of("PAYLOAD_TOO_LARGE", "上传文件过大"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> resourceNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("NOT_FOUND", "接口不存在"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException exception) {
        String message = reason(exception);
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiErrorResponse.of(codeFor(exception.getStatusCode().value(), message), message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> dataConflict(DataIntegrityViolationException exception) {
        log.warn("Database constraint rejected an API request", exception);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("DATA_CONFLICT", "数据状态冲突，请刷新后重试"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> internal(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API failure for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.internalServerError()
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "服务暂时无法完成请求，请稍后重试"));
    }

    private Map<String, String> fieldErrors(List<FieldError> errors) {
        Map<String, String> result = new LinkedHashMap<>();
        errors.stream()
                .sorted(Comparator.comparingInt(this::fieldPriority).thenComparing(FieldError::getField))
                .forEach(error -> result.putIfAbsent(error.getField(), safeMessage(error)));
        return result;
    }

    private int fieldPriority(FieldError error) {
        int index = FIELD_PRIORITY.indexOf(error.getField());
        return index < 0 ? FIELD_PRIORITY.size() : index;
    }

    private String safeMessage(FieldError error) {
        return error.getDefaultMessage() == null || error.getDefaultMessage().isBlank()
                ? "字段内容不正确" : error.getDefaultMessage();
    }

    private String reason(ResponseStatusException exception) {
        return exception.getReason() == null || exception.getReason().isBlank()
                ? "请求失败（HTTP " + exception.getStatusCode().value() + "）"
                : exception.getReason();
    }

    private String codeFor(int status, String message) {
        if (message.contains("用户名已存在")) return "USERNAME_TAKEN";
        if (message.contains("账号不存在")) return "ACCOUNT_NOT_FOUND";
        if (message.contains("用户名或密码错误")) return "INVALID_CREDENTIALS";
        if (message.contains("请填写邀请码")) return "INVITE_CODE_REQUIRED";
        if (message.contains("邀请码无效")) return "INVITE_CODE_INVALID";
        if (message.contains("邀请码已删除")) return "INVITE_CODE_DELETED";
        if (message.contains("邀请码已停用")) return "INVITE_CODE_DISABLED";
        if (message.contains("邀请码使用次数已达上限")) return "INVITE_CODE_EXHAUSTED";
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 422 -> "UNPROCESSABLE_ENTITY";
            case 429 -> "TOO_MANY_REQUESTS";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
    }
}
