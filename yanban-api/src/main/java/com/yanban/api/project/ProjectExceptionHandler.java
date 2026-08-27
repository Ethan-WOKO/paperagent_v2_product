package com.yanban.api.project;

import com.yanban.api.error.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice(assignableTypes = ProjectController.class)
class ProjectExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> uploadTooLarge(MaxUploadSizeExceededException ignored) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiErrorResponse.of(
                "PROJECT_UPLOAD_TOO_LARGE",
                "The selected Project folder is too large. Remove generated or binary files and try again."));
    }

    @ExceptionHandler(InvalidProjectPathException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse invalidPath() {
        return ApiErrorResponse.of("INVALID_PROJECT_PATH", "Invalid Project path");
    }

    @ExceptionHandler(ProjectTraversalLimitException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ApiErrorResponse traversalLimit() {
        return ApiErrorResponse.of("PROJECT_LIMIT_EXCEEDED", "Project traversal limit exceeded");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        String message = StringUtils.hasText(exception.getReason())
                ? exception.getReason() : "Project request failed (HTTP " + status + ").";
        String code = status == HttpStatus.BAD_GATEWAY.value()
                ? isStorageFailure(message) ? "PROJECT_STORAGE_UNAVAILABLE" : "PROJECT_PLAN_FAILED"
                : "PROJECT_REQUEST_FAILED";
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiErrorResponse.of(code, message));
    }

    private boolean isStorageFailure(String message) {
        return message.startsWith("Project file ") || message.startsWith("Project manifest ")
                || message.startsWith("Project object ");
    }
}
