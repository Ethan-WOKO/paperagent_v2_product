package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/agent-engine/task-state")
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
final class ReactPlanTaskStateController {
    private final ReactPlanTaskStateService state;
    private final ReactPlanRuntimeProperties properties;

    ReactPlanTaskStateController(ReactPlanTaskStateService state,
                                 ReactPlanRuntimeProperties properties) {
        this.state = state;
        this.properties = properties;
    }

    @PostMapping("/checkpoints")
    CheckpointSaved save(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CheckpointSave request) {
        authenticate(authorization);
        if (!"1.0".equals(request.contractVersion()) || request.checkpoint() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CHECKPOINT_REQUEST_INVALID");
        }
        return new CheckpointSaved("1.0", state.save(
                request.taskId(), request.requestDigest(),
                request.expectedRevision(), request.checkpoint()));
    }

    @PostMapping("/events")
    Accepted append(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody EventAppend request) {
        authenticate(authorization);
        if (!"1.0".equals(request.contractVersion()) || request.event() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EVENT_REQUEST_INVALID");
        }
        state.appendEvent(request.event());
        return new Accepted("1.0", true);
    }

    @GetMapping("/checkpoints")
    Recoverable checkpoints(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authenticate(authorization);
        return new Recoverable("1.0", state.stored());
    }

    @GetMapping("/tasks/{taskId}/events")
    StoredEvents events(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String taskId) {
        authenticate(authorization);
        return new StoredEvents("1.0", state.events(taskId));
    }

    @PostMapping("/tasks/{taskId}/authorize-recovery")
    RecoveryAuthorized authorize(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String taskId,
            @RequestBody RecoveryRequest request) {
        authenticate(authorization);
        if (!"1.0".equals(request.contractVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RECOVERY_REQUEST_INVALID");
        }
        EngineTaskGrant grant = state.authorizeRecovery(taskId, request.requestDigest());
        return new RecoveryAuthorized("1.0", grant.value(), grant.expiresAt().toString());
    }

    private void authenticate(String authorization) {
        String expected = "Bearer " + properties.getEngineServiceToken();
        if (authorization == null || !MessageDigest.isEqual(
                authorization.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ENGINE_SERVICE_UNAUTHORIZED");
        }
    }

    record CheckpointSave(String contractVersion, String taskId, String requestDigest,
                          Long expectedRevision, JsonNode checkpoint) { }
    record CheckpointSaved(String contractVersion, long checkpointRevision) { }
    record EventAppend(String contractVersion, JsonNode event) { }
    record Accepted(String contractVersion, boolean accepted) { }
    record Recoverable(String contractVersion,
                       List<ReactPlanTaskStateService.StoredCheckpoint> tasks) { }
    record StoredEvents(String contractVersion, List<JsonNode> events) { }
    record RecoveryRequest(String contractVersion, String requestDigest) { }
    record RecoveryAuthorized(String contractVersion, String taskGrant, String expiresAt) { }
}
