package com.yanban.api.agent.v2.chain.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ProjectChainApiExceptionHandler {
    @ExceptionHandler(ProjectChainApiException.class)
    ResponseEntity<ProjectChainApiErrorResponse> chain(
            ProjectChainApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ProjectChainApiErrorResponse(exception.code()));
    }
}
