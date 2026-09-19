package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ReactPlanTaskStateController.class);
    private static final int DATABASE_ATTEMPTS = 3;
    private final ReactPlanTaskStateService state;
    private final ReactPlanRuntimeProperties properties;
    private final ReactPlanTaskSchedulerService scheduler;

    ReactPlanTaskStateController(ReactPlanTaskStateService state,
                                 ReactPlanRuntimeProperties properties,
                                 ReactPlanTaskSchedulerService scheduler) {
        this.state = state;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @PostMapping("/checkpoints")
    CheckpointSaved save(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CheckpointSave request) {
        authenticate(authorization);
        if (!"1.0".equals(request.contractVersion()) || request.checkpoint() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CHECKPOINT_REQUEST_INVALID");
        }
        return retryTransientDatabaseConflict(request.taskId(), () -> new CheckpointSaved("1.0", state.save(
                request.taskId(), request.requestDigest(),
                request.expectedRevision(), request.checkpoint(), request.lease())));
    }

    @PostMapping("/events")
    Accepted append(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody EventAppend request) {
        authenticate(authorization);
        if (!"1.0".equals(request.contractVersion()) || request.event() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EVENT_REQUEST_INVALID");
        }
        String taskId = request.event().path("taskId").asText("unknown");
        return retryTransientDatabaseConflict(taskId, () -> {
            state.appendEvent(request.event(), request.lease());
            return new Accepted("1.0", true);
        });
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

    @GetMapping("/tasks/{taskId}/checkpoint")
    ReactPlanTaskStateService.StoredCheckpoint checkpoint(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String taskId) {
        authenticate(authorization);
        return state.stored(taskId);
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

    @PostMapping("/claims/next")
    ClaimResponse claimNext(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ClaimRequest request) {
        authenticate(authorization);
        if (request == null || !"1.0".equals(request.contractVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CLAIM_REQUEST_INVALID");
        }
        return new ClaimResponse("1.0", scheduler.claimNext(request.owner()));
    }

    @PostMapping("/tasks/{taskId}/lease/renew")
    ReactPlanTaskSchedulerService.LeaseHeartbeat renew(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String taskId,
            @RequestBody LeaseRequest request) {
        authenticate(authorization);
        validateLeaseRequest(request);
        return retryTransientDatabaseConflict(taskId, () -> scheduler.renew(taskId, request.lease()));
    }

    @PostMapping("/tasks/{taskId}/claim")
    ClaimResponse claimTask(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String taskId,
            @RequestBody ClaimRequest request) {
        authenticate(authorization);
        if (request == null || !"1.0".equals(request.contractVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CLAIM_REQUEST_INVALID");
        }
        return new ClaimResponse("1.0", scheduler.claimTask(taskId, request.owner()));
    }

    @PostMapping("/tasks/{taskId}/lease/release")
    Accepted release(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String taskId,
            @RequestBody LeaseRequest request) {
        authenticate(authorization);
        validateLeaseRequest(request);
        scheduler.release(taskId, request.lease());
        return new Accepted("1.0", true);
    }

    @PostMapping("/tasks/{taskId}/cancel-request")
    Accepted requestCancellation(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String taskId) {
        authenticate(authorization);
        scheduler.requestCancellation(taskId);
        return new Accepted("1.0", true);
    }

    private void authenticate(String authorization) {
        String expected = "Bearer " + properties.getEngineServiceToken();
        if (authorization == null || !MessageDigest.isEqual(
                authorization.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ENGINE_SERVICE_UNAUTHORIZED");
        }
    }

    private static void validateLeaseRequest(LeaseRequest request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.lease() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TASK_LEASE_REQUEST_INVALID");
        }
    }

    private static <T> T retryTransientDatabaseConflict(String taskId, Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (TransientDataAccessException transientFailure) {
                if (attempt >= DATABASE_ATTEMPTS) throw transientFailure;
                log.warn("reactplan_database_retry taskId={} traceId={} attempt={} reason={}",
                        taskId, ReactPlanTraceIds.forTask(taskId), attempt,
                        transientFailure.getClass().getSimpleName());
            }
        }
    }

    record CheckpointSave(String contractVersion, String taskId, String requestDigest,
                          Long expectedRevision, JsonNode checkpoint,
                          ReactPlanTaskSchedulerService.Lease lease) { }
    record CheckpointSaved(String contractVersion, long checkpointRevision) { }
    record EventAppend(String contractVersion, JsonNode event,
                       ReactPlanTaskSchedulerService.Lease lease) { }
    record Accepted(String contractVersion, boolean accepted) { }
    record Recoverable(String contractVersion,
                       List<ReactPlanTaskStateService.StoredCheckpoint> tasks) { }
    record StoredEvents(String contractVersion, List<JsonNode> events) { }
    record RecoveryRequest(String contractVersion, String requestDigest) { }
    record RecoveryAuthorized(String contractVersion, String taskGrant, String expiresAt) { }
    record ClaimRequest(String contractVersion, String owner) { }
    record ClaimResponse(String contractVersion, ReactPlanTaskSchedulerService.ClaimedTask task) { }
    record LeaseRequest(String contractVersion, ReactPlanTaskSchedulerService.Lease lease) { }
}
