package com.yanban.api.agent.reactplan.gateway;

import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.Problem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(assignableTypes = AgentEngineGatewayController.class)
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEngineGatewayExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentEngineGatewayExceptionHandler.class);

    @ExceptionHandler(EngineGatewayException.class)
    ResponseEntity<Problem> gateway(EngineGatewayException failure) {
        String category = switch (failure.status()) {
            case UNAUTHORIZED, FORBIDDEN -> "authorization";
            default -> failure.code().startsWith("SANDBOX_") ? "sandbox_system" : "request";
        };
        return ResponseEntity.status(failure.status()).body(new Problem(
                "1.0", failure.code(), category,
                "The product tool gateway rejected the request.", false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> internal(Exception failure) {
        log.error("Unexpected Agent Engine gateway failure", failure);
        return ResponseEntity.internalServerError().body(new Problem(
                "1.0", "ENGINE_GATEWAY_INTERNAL", "internal",
                "The product tool gateway could not complete the request.", true));
    }
}
