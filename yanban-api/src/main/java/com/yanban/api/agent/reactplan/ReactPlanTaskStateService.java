package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineTaskGrantService;
import com.yanban.api.agent.reactplan.gateway.AgentEngineObservationReader;
import com.yanban.api.agent.reactplan.gateway.EngineModelRouteCandidate;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
class ReactPlanTaskStateService {
    private static final Logger log = LoggerFactory.getLogger(ReactPlanTaskStateService.class);
    private static final int MAX_CHECKPOINT_BYTES = 1_048_576;
    private static final int MAX_EVENT_BYTES = 32_768;
    private static final Set<String> STATES = Set.of(
            "queued", "running", "waiting_user", "succeeded", "failed", "cancelled");
    private static final Set<String> RECOVERABLE = Set.of("queued", "running", "waiting_user");
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "taskgrant", "apikey", "authorization", "servicetoken", "accesstoken", "refreshtoken");

    private final ObjectMapper json;
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final ReactPlanTaskEventRepository events;
    private final ReactPlanTurnIntakeRepository intakes;
    private final AgentTurnProductContextResolver contexts;
    private final AgentEngineTaskGrantService grants;
    private final AgentEngineObservationReader observations;
    private final ReactPlanUsageSettlementRepository usageSettlements;
    private final ReactPlanTaskSchedulerService scheduler;
    private final ApplicationEventPublisher applicationEvents;

    ReactPlanTaskStateService(ObjectMapper json,
                              ReactPlanTaskCheckpointRepository checkpoints,
                              ReactPlanTaskEventRepository events,
                              ReactPlanTurnIntakeRepository intakes,
                              AgentTurnProductContextResolver contexts,
                              AgentEngineTaskGrantService grants,
                              AgentEngineObservationReader observations,
                              ReactPlanUsageSettlementRepository usageSettlements,
                              ReactPlanTaskSchedulerService scheduler,
                              ApplicationEventPublisher applicationEvents) {
        this.json = json;
        this.checkpoints = checkpoints;
        this.events = events;
        this.intakes = intakes;
        this.contexts = contexts;
        this.grants = grants;
        this.observations = observations;
        this.usageSettlements = usageSettlements;
        this.scheduler = scheduler;
        this.applicationEvents = applicationEvents;
    }

    @Transactional
    long save(String taskId, String requestDigest, Long expectedRevision, JsonNode checkpoint,
              ReactPlanTaskSchedulerService.Lease lease) {
        if (expectedRevision != null && expectedRevision < 1) badRequest("CHECKPOINT_REVISION_INVALID");
        CheckpointIdentity identity = validateCheckpoint(taskId, requestDigest, checkpoint);
        String serialized = serialize(checkpoint, MAX_CHECKPOINT_BYTES, "checkpoint");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // New task admission and task claiming must acquire locks in the same
        // scheduler -> checkpoint order. Looking up the checkpoint first and
        // then locking the scheduler can deadlock with claimNext(), which
        // deliberately holds the scheduler row before scanning checkpoints.
        if (expectedRevision == null) scheduler.lockScheduler();
        ReactPlanTaskCheckpointEntity existing = checkpoints.findLockedByTaskId(taskId).orElse(null);
        if (existing == null) {
            if (expectedRevision != null) conflict("CHECKPOINT_REVISION_CONFLICT");
            ReactPlanTurnIntakeEntity intake = requireIntake(taskId);
            requireIdentity(identity, intake);
            scheduler.assertQueueCapacity(intake.userId());
            ReactPlanTaskCheckpointEntity created = new ReactPlanTaskCheckpointEntity(
                    taskId, requestDigest, intake.userId(), intake.sessionId(), intake.turnId(),
                    identity.state(), identity.lastSequence(), serialized, now);
            boolean becameTerminal = settleUsageIfTerminal(created);
            long revision = checkpoints.saveAndFlush(created).checkpointRevision();
            if (becameTerminal) requestConversationSummary(intake);
            return revision;
        }
        if (!existing.requestDigest().equals(requestDigest)) conflict("TASK_DIGEST_CONFLICT");
        if (expectedRevision == null) {
            if (existing.checkpointJson().equals(serialized)) return existing.checkpointRevision();
            conflict("CHECKPOINT_ALREADY_EXISTS");
        }
        if (expectedRevision.longValue() + 1 == existing.checkpointRevision()
                && existing.checkpointJson().equals(serialized)) {
            return existing.checkpointRevision();
        }
        if (expectedRevision.longValue() != existing.checkpointRevision()) {
            conflict("CHECKPOINT_REVISION_CONFLICT");
        }
        scheduler.requireOwned(existing, lease);
        long latestEvent = latestEventSequence(taskId);
        if (identity.lastSequence() < latestEvent || identity.lastSequence() < existing.lastSequence()) {
            conflict("CHECKPOINT_SEQUENCE_REGRESSION");
        }
        existing.update(identity.state(), identity.lastSequence(), serialized, now);
        boolean becameTerminal = settleUsageIfTerminal(existing);
        long revision = checkpoints.saveAndFlush(existing).checkpointRevision();
        if (becameTerminal) requestConversationSummary(requireIntake(taskId));
        return revision;
    }

    private boolean settleUsageIfTerminal(ReactPlanTaskCheckpointEntity checkpoint) {
        if (!TERMINAL.contains(checkpoint.state()) || checkpoint.usageSettled()) return false;
        long promptTokens = 0L;
        long completionTokens = 0L;
        for (AgentEngineObservationReader.ModelFact model : observations.models(checkpoint.taskId())) {
            if (!"SUCCEEDED".equals(model.state())) continue;
            promptTokens = safeAdd(promptTokens, model.promptTokens());
            completionTokens = safeAdd(completionTokens, model.completionTokens());
        }
        checkpoint.settleUsage(promptTokens, completionTokens);
        usageSettlements.save(new ReactPlanUsageSettlementEntity(
                checkpoint.taskId(), checkpoint.userId(), promptTokens, completionTokens,
                LocalDateTime.now(ZoneOffset.UTC)));
        applicationEvents.publishEvent(new ReactPlanUsageSettlementRequested(
                checkpoint.taskId()));
        log.info("reactplan_usage taskId={} traceId={} outcome=queued promptTokens={} completionTokens={}",
                checkpoint.taskId(), ReactPlanTraceIds.forTask(checkpoint.taskId()),
                promptTokens, completionTokens);
        return true;
    }

    private void requestConversationSummary(ReactPlanTurnIntakeEntity intake) {
        applicationEvents.publishEvent(new ReactPlanConversationSummaryRequested(
                intake.userId(), intake.sessionId(), intake.id()));
    }

    private static long safeAdd(long left, long right) {
        try { return Math.addExact(left, Math.max(0L, right)); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    @Transactional
    void appendEvent(JsonNode event, ReactPlanTaskSchedulerService.Lease lease) {
        rejectSecrets(event);
        String taskId = requiredText(event, "taskId");
        long sequence = requiredNonNegativeLong(event, "sequence");
        if (sequence < 1) badRequest("EVENT_SEQUENCE_INVALID");
        ReactPlanTaskCheckpointEntity checkpoint = checkpoints.findLockedByTaskId(taskId)
                .orElseThrow(() -> notFound("TASK_NOT_FOUND"));
        String serialized = serialize(event, MAX_EVENT_BYTES, "event");
        ReactPlanTaskEventEntity replay = events.findByTaskIdAndSequenceNumber(taskId, sequence).orElse(null);
        if (replay != null) {
            if (replay.eventJson().equals(serialized)) return;
            conflict("EVENT_SEQUENCE_CONFLICT");
        }
        scheduler.requireOwned(checkpoint, lease);
        long latest = latestEventSequence(taskId);
        if (sequence != latest + 1) conflict("EVENT_SEQUENCE_GAP");
        Instant occurredAt;
        try { occurredAt = Instant.parse(requiredText(event, "occurredAt")); }
        catch (RuntimeException invalid) { throw badRequest("EVENT_TIME_INVALID"); }
        events.saveAndFlush(new ReactPlanTaskEventEntity(
                checkpoint.taskId(), sequence, serialized,
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC)));
        String type = event.path("type").asText("unknown");
        String phase = "tool".equals(type)
                ? event.path("registeredToolName").asText(event.path("name").asText("tool")) : type;
        String outcome = "tool".equals(type)
                ? event.path("state").asText("unknown") : event.path("state").asText("recorded");
        log.info("reactplan_event taskId={} traceId={} phase={} outcome={} sequence={}",
                taskId, ReactPlanTraceIds.forTask(taskId), phase, outcome, sequence);
    }

    @Transactional(readOnly = true)
    List<StoredCheckpoint> stored() {
        return checkpoints.findAllByOrderByUpdatedAtAsc().stream()
                .map(entity -> new StoredCheckpoint(entity.checkpointRevision(), parse(entity.checkpointJson())))
                .toList();
    }

    @Transactional(readOnly = true)
    StoredCheckpoint stored(String taskId) {
        ReactPlanTaskCheckpointEntity entity = checkpoints.findById(taskId)
                .orElseThrow(() -> notFound("TASK_NOT_FOUND"));
        return new StoredCheckpoint(entity.checkpointRevision(), parse(entity.checkpointJson()));
    }

    @Transactional(readOnly = true)
    List<JsonNode> events(String taskId) {
        checkpoints.findById(taskId).orElseThrow(() -> notFound("TASK_NOT_FOUND"));
        return events.findByTaskIdOrderBySequenceNumberAsc(taskId).stream()
                .map(event -> parse(event.eventJson())).toList();
    }

    @Transactional(readOnly = true)
    EngineTaskGrant authorizeRecovery(String taskId, String requestDigest) {
        ReactPlanTaskCheckpointEntity checkpoint = checkpoints.findById(taskId)
                .orElseThrow(() -> notFound("TASK_NOT_FOUND"));
        if (!RECOVERABLE.contains(checkpoint.state())) conflict("TASK_NOT_RECOVERABLE");
        if (!checkpoint.requestDigest().equals(requestDigest)) conflict("TASK_DIGEST_CONFLICT");
        ReactPlanTurnIntakeEntity intake = requireIntake(taskId);
        CheckpointIdentity identity = validateCheckpoint(
                taskId, requestDigest, parse(checkpoint.checkpointJson()));
        requireIdentity(identity, intake);
        return grants.issue(taskId, requestDigest, intake.userId(), intake.turnId(),
                identity.modelProvider(), identity.modelName(), identity.modelFallbacks());
    }

    private CheckpointIdentity validateCheckpoint(
            String taskId, String requestDigest, JsonNode checkpoint) {
        rejectSecrets(checkpoint);
        JsonNode view = checkpoint.path("view");
        JsonNode authority = checkpoint.path("authority");
        if (!taskId.matches("task\\.[a-f0-9]{64}")
                || !requestDigest.matches("[a-f0-9]{64}")
                || !taskId.equals(requiredText(view, "taskId"))
                || !requestDigest.equals(requiredText(view, "requestDigest"))
                || !requestDigest.equals(ReactPlanCanonicalJson.digest(json, authority))) {
            badRequest("CHECKPOINT_IDENTITY_INVALID");
        }
        String state = requiredText(view, "state");
        if (!STATES.contains(state)) badRequest("CHECKPOINT_STATE_INVALID");
        long sequence = requiredNonNegativeLong(view, "lastSequence");
        String sessionRef = requiredText(authority, "sessionRef");
        if (!sessionRef.matches("session\\.[1-9][0-9]*")) badRequest("CHECKPOINT_SESSION_INVALID");
        long sessionId;
        try { sessionId = Long.parseLong(sessionRef.substring("session.".length())); }
        catch (NumberFormatException invalid) { throw badRequest("CHECKPOINT_SESSION_INVALID"); }
        long projectId;
        try { projectId = Long.parseLong(requiredText(authority.path("project"), "projectId")); }
        catch (NumberFormatException invalid) { throw badRequest("CHECKPOINT_PROJECT_INVALID"); }
        String projectVersion = requiredText(authority.path("project"), "projectVersion");
        String modelProvider = requiredText(authority.path("model"), "provider");
        String modelName = requiredText(authority.path("model"), "model");
        var modelFallbacks = ReactPlanCheckpointModelRoutes.fallbacks(
                authority.path("model"));
        return new CheckpointIdentity(state, sequence, sessionId, projectId, projectVersion,
                modelProvider, modelName, modelFallbacks);
    }

    private void requireIdentity(CheckpointIdentity identity, ReactPlanTurnIntakeEntity intake) {
        VerifiedAgentTurnProductContext context = contexts.resolve(intake.userId(), intake.turnId());
        if (identity.sessionId() != intake.sessionId()
                || !Long.valueOf(identity.sessionId()).equals(context.identity().sessionId())
                || !Long.valueOf(identity.projectId()).equals(context.identity().projectId())
                || !context.projectVersionId().orElse("").equals(identity.projectVersion())
                || !ReactPlanRuntimeService.taskId(intake.userId(), intake.turnId()).equals(intake.taskId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CHECKPOINT_AUTHORITY_MISMATCH");
        }
    }

    private ReactPlanTurnIntakeEntity requireIntake(String taskId) {
        return intakes.findByTaskId(taskId).orElseThrow(() -> notFound("TASK_INTAKE_NOT_FOUND"));
    }

    private long latestEventSequence(String taskId) {
        return events.findTopByTaskIdOrderBySequenceNumberDesc(taskId)
                .map(ReactPlanTaskEventEntity::sequenceNumber).orElse(0L);
    }

    private String serialize(JsonNode value, int limit, String kind) {
        try {
            String result = json.writeValueAsString(value);
            if (result.getBytes(StandardCharsets.UTF_8).length > limit) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        kind.toUpperCase() + "_TOO_LARGE");
            }
            return result;
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(kind + " serialization failed", impossible);
        }
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Persisted ReAct state is corrupt", corrupt);
        }
    }

    private void rejectSecrets(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (FORBIDDEN_KEYS.contains(entry.getKey().replace("_", "").toLowerCase())) {
                    badRequest("CHECKPOINT_SECRET_FIELD_FORBIDDEN");
                }
                rejectSecrets(entry.getValue());
            });
        } else if (node.isArray()) node.forEach(this::rejectSecrets);
    }

    private static String requiredText(JsonNode object, String field) {
        String value = object.path(field).asText("");
        if (value.isBlank()) badRequest("CHECKPOINT_FIELD_INVALID");
        return value;
    }

    private static long requiredNonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || value.asLong() < 0) badRequest("CHECKPOINT_FIELD_INVALID");
        return value.asLong();
    }

    private static ResponseStatusException badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
    private static ResponseStatusException conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
    private static ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }

    record StoredCheckpoint(long checkpointRevision, JsonNode checkpoint) { }
    private record CheckpointIdentity(String state, long lastSequence, long sessionId,
                                      long projectId, String projectVersion,
                                      String modelProvider, String modelName,
                                      List<EngineModelRouteCandidate> modelFallbacks) { }
}
