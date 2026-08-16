package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/react-agent/sessions/{sessionId}/tasks")
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
final class ReactPlanTurnIntakeController {
    private final ReactPlanTurnIntakeService intake;

    ReactPlanTurnIntakeController(ReactPlanTurnIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping
    ResponseEntity<JsonNode> start(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable long sessionId,
            @RequestBody ReactPlanSessionTaskRequest request) {
        return ResponseEntity.accepted().body(intake.start(userId, sessionId, request));
    }
}
