package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanExecutionLease;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanRunLeaseService;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Builds and verifies sanitized, server-owned recovery checkpoints for existing Project Plans. */
@Service
public class AgentPlanCheckpointService {

    static final String SCHEMA = "agent_plan_checkpoint_v1";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema", "version", "planId", "userId", "sessionId", "projectId", "projectVersion",
            "manifest", "planDigest", "allowedTools", "maxPlanSeconds", "maxStepAttempts",
            "maxDuplicateToolCalls", "maxToolCallsPerStep", "maxToolCalls", "consumedAttempts", "consumedToolCalls",
            "steps", "receipts", "remainingStepKeys", "cancelled");
    private static final Set<String> MANIFEST_FIELDS = Set.of("relativePath", "sizeBytes", "sha256");
    private static final Set<String> STEP_FIELDS = Set.of(
            "id", "stepKey", "status", "attemptCount", "resultHash");
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "eventId", "stepId", "eventType", "idempotencyKey", "payloadHash");
    private static final Set<String> RECEIPT_EVENTS = Set.of(
            "step_tool_observation", "step_tool_result", "step_project_evidence", "step_domain_consistency_fact");

    private final ObjectMapper json;
    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentPlanEventRepository events;
    private final AgentPlanRunLeaseService leases;
    private final ProjectService projects;

    public AgentPlanCheckpointService(ObjectMapper json, AgentPlanRepository plans,
                                      AgentPlanStepRepository steps, AgentPlanEventRepository events,
                                      AgentPlanRunLeaseService leases, ProjectService projects) {
        this.json = json;
        this.plans = plans;
        this.steps = steps;
        this.events = events;
        this.leases = leases;
        this.projects = projects;
    }

    public Validation initializeOrValidate(AgentPlanExecutionLease lease, ResolvedToolPolicy currentPolicy,
                                           BudgetCeiling ceiling) {
        AgentPlan plan = ownedPlan(lease);
        ProjectRuntimeContext context = ProjectPlanEnvelope.restore(json, plan.getRawPlanJson(), plan.getUserId());
        if (context == null || !context.userId().equals(lease.userId())) {
            throw new IllegalStateException("durable Plan is missing its trusted Project identity");
        }
        ProjectManifestResponse manifest = projects.manifest(lease.userId(), context.projectId());
        if (!lease.recovery()) {
            AgentPlan stored = saveBoundary(lease, currentPolicy, ceiling, manifest);
            return new Validation(false, stored.getCheckpointVersion(), context, manifest, ceiling);
        }
        Checkpoint checkpoint = parseAndValidate(plan, manifest, currentPolicy, ceiling);
        BudgetCeiling persistedCeiling = new BudgetCeiling(checkpoint.maxPlanSeconds(),
                checkpoint.maxStepAttempts(), checkpoint.maxDuplicateToolCalls(), checkpoint.maxToolCalls());
        return new Validation(true, checkpoint.version(), context, manifest, persistedCeiling);
    }

    public AgentPlan saveBoundary(AgentPlanExecutionLease lease, ResolvedToolPolicy currentPolicy,
                                  BudgetCeiling ceiling) {
        AgentPlan plan = ownedPlan(lease);
        ProjectRuntimeContext context = ProjectPlanEnvelope.restore(json, plan.getRawPlanJson(), plan.getUserId());
        if (context == null) throw new IllegalStateException("durable Plan Project identity is missing");
        return saveBoundary(lease, currentPolicy, ceiling,
                projects.manifest(lease.userId(), context.projectId()));
    }

    /** Returns the server-observed total budget remaining at the current fenced dispatch boundary. */
    public int remainingToolCalls(AgentPlanExecutionLease lease, BudgetCeiling ceiling) {
        AgentPlan plan = ownedPlan(lease);
        int consumed = consumedToolCalls(safeEvents(plan.getId()));
        if (consumed > ceiling.maxToolCalls()) {
            throw new IllegalStateException("durable Plan tool budget is exhausted");
        }
        return ceiling.maxToolCalls() - consumed;
    }

    private AgentPlan saveBoundary(AgentPlanExecutionLease lease, ResolvedToolPolicy currentPolicy,
                                   BudgetCeiling ceiling, ProjectManifestResponse manifest) {
        AgentPlan plan = ownedPlan(lease);
        List<AgentPlanStep> planSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
        List<AgentPlanEvent> planEvents = safeEvents(plan.getId());
        long version = (plan.getCheckpointVersion() == null ? 0L : plan.getCheckpointVersion()) + 1L;
        Checkpoint checkpoint = snapshot(plan, manifest, planSteps, planEvents, currentPolicy, ceiling, version);
        String serialized = writeCanonical(checkpoint);
        return leases.storeCheckpoint(lease, serialized, sha256(serialized), version);
    }

    private Checkpoint parseAndValidate(AgentPlan plan, ProjectManifestResponse manifest,
                                        ResolvedToolPolicy currentPolicy, BudgetCeiling ceiling) {
        if (!StringUtils.hasText(plan.getCheckpointJson()) || !StringUtils.hasText(plan.getCheckpointHash())
                || plan.getCheckpointVersion() == null || plan.getCheckpointVersion() < 1) {
            throw new IllegalStateException("durable Plan recovery checkpoint is missing");
        }
        if (!sha256(plan.getCheckpointJson()).equals(plan.getCheckpointHash())) {
            throw new IllegalStateException("durable Plan recovery checkpoint was tampered");
        }
        try {
            JsonNode root = json.readTree(plan.getCheckpointJson());
            requireFields(root, ROOT_FIELDS, "checkpoint");
            requireArrayObjects(root.path("manifest"), MANIFEST_FIELDS, "checkpoint manifest");
            requireArrayObjects(root.path("steps"), STEP_FIELDS, "checkpoint steps");
            requireArrayObjects(root.path("receipts"), RECEIPT_FIELDS, "checkpoint receipts");
            Checkpoint checkpoint = json.treeToValue(root, Checkpoint.class);
            validate(checkpoint, plan, manifest, currentPolicy, ceiling);
            return checkpoint;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("durable Plan recovery checkpoint is invalid", exception);
        }
    }

    private void validate(Checkpoint checkpoint, AgentPlan plan, ProjectManifestResponse manifest,
                          ResolvedToolPolicy currentPolicy, BudgetCeiling ceiling) {
        if (!SCHEMA.equals(checkpoint.schema()) || checkpoint.version() != plan.getCheckpointVersion()
                || checkpoint.planId() != plan.getId() || checkpoint.userId() != plan.getUserId()
                || checkpoint.sessionId() != plan.getSessionId()
                || checkpoint.projectId() != manifest.projectId() || checkpoint.cancelled()) {
            throw new IllegalStateException("durable Plan checkpoint identity or cancellation state is invalid");
        }
        List<ManifestItem> currentManifest = manifest(manifest);
        if (!checkpoint.projectVersion().equals(manifest.version()) || !checkpoint.manifest().equals(currentManifest)) {
            throw new IllegalStateException("durable Plan checkpoint is STALE for the current Project version or file hash");
        }
        List<AgentPlanStep> currentSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
        if (!checkpoint.planDigest().equals(planDigest(plan, currentSteps))) {
            throw new IllegalStateException("durable Plan or envelope integrity validation failed");
        }
        List<String> expectedTools = persistedTools(currentSteps);
        if (!checkpoint.allowedTools().equals(expectedTools)
                || currentPolicy == null || !currentPolicy.allowedTools().containsAll(expectedTools)) {
            throw new IllegalStateException("durable Plan tool authority was revoked or changed");
        }
        if (checkpoint.maxPlanSeconds() != ceiling.maxPlanSeconds()
                || checkpoint.maxStepAttempts() != ceiling.maxStepAttempts()
                || checkpoint.maxDuplicateToolCalls() != effectiveMaxDuplicateToolCalls(currentPolicy, ceiling)
                || checkpoint.maxToolCallsPerStep() != Math.max(0, currentPolicy.maxToolCalls())
                || checkpoint.maxToolCalls() > ceiling.maxToolCalls()) {
            throw new IllegalStateException("durable Plan recovery budget ceiling changed");
        }
        int attempts = consumedAttempts(currentSteps);
        int toolCalls = consumedToolCalls(safeEvents(plan.getId()));
        if (attempts < checkpoint.consumedAttempts() || toolCalls < checkpoint.consumedToolCalls()
                || attempts > checkpoint.maxStepAttempts() * Math.max(1, currentSteps.size())
                || toolCalls > checkpoint.maxToolCalls()) {
            throw new IllegalStateException("durable Plan recovery budget is inconsistent or exhausted");
        }
        validateCompletedSteps(checkpoint.steps(), currentSteps);
        validateReceipts(checkpoint.receipts(), safeEvents(plan.getId()));
    }

    private Checkpoint snapshot(AgentPlan plan, ProjectManifestResponse manifest,
                                List<AgentPlanStep> planSteps, List<AgentPlanEvent> planEvents,
                                ResolvedToolPolicy currentPolicy, BudgetCeiling ceiling, long version) {
        List<String> allowedTools = persistedTools(planSteps);
        if (currentPolicy == null || !currentPolicy.allowedTools().containsAll(allowedTools)) {
            throw new IllegalStateException("durable Plan tool authority is unavailable");
        }
        int maxToolCalls = ceiling.maxToolCalls();
        int attempts = consumedAttempts(planSteps);
        int toolCalls = consumedToolCalls(planEvents);
        if (attempts > ceiling.maxStepAttempts() * Math.max(1, planSteps.size()) || toolCalls > maxToolCalls) {
            throw new IllegalStateException("durable Plan budget is exhausted");
        }
        List<StepState> states = planSteps.stream().map(step -> new StepState(
                step.getId(), step.getStepKey(), step.getStatus(), Math.max(0, step.getAttemptCount()),
                StringUtils.hasText(step.getResult()) ? sha256(step.getResult()) : null)).toList();
        List<EventReceipt> receipts = planEvents.stream()
                .filter(event -> RECEIPT_EVENTS.contains(event.getEventType()))
                .map(event -> new EventReceipt(event.getId(), event.getStepId(), event.getEventType(),
                        event.getIdempotencyKey(), sha256(event.getPayloadJson() == null ? "" : event.getPayloadJson())))
                .toList();
        List<String> remaining = planSteps.stream()
                .filter(step -> !("COMPLETED".equals(step.getStatus()) || "DEGRADED".equals(step.getStatus())
                        || "SUPERSEDED".equals(step.getStatus())))
                .map(AgentPlanStep::getStepKey).toList();
        return new Checkpoint(SCHEMA, version, plan.getId(), plan.getUserId(), plan.getSessionId(),
                manifest.projectId(), manifest.version(), manifest(manifest), planDigest(plan, planSteps),
                allowedTools, ceiling.maxPlanSeconds(), ceiling.maxStepAttempts(),
                effectiveMaxDuplicateToolCalls(currentPolicy, ceiling),
                Math.max(0, currentPolicy.maxToolCalls()), maxToolCalls,
                attempts, toolCalls, states, receipts, remaining, false);
    }

    private void validateCompletedSteps(List<StepState> checkpointSteps, List<AgentPlanStep> currentSteps) {
        var current = currentSteps.stream().collect(java.util.stream.Collectors.toMap(AgentPlanStep::getId, step -> step));
        for (StepState saved : checkpointSteps) {
            if (!("COMPLETED".equals(saved.status()) || "DEGRADED".equals(saved.status())
                    || "SUPERSEDED".equals(saved.status()))) continue;
            AgentPlanStep step = current.get(saved.id());
            String resultHash = step == null || !StringUtils.hasText(step.getResult()) ? null : sha256(step.getResult());
            if (step == null || !saved.stepKey().equals(step.getStepKey()) || !saved.status().equals(step.getStatus())
                    || saved.attemptCount() != Math.max(0, step.getAttemptCount())
                    || !java.util.Objects.equals(saved.resultHash(), resultHash)) {
                throw new IllegalStateException("completed durable Plan step was changed after checkpoint");
            }
        }
    }

    private void validateReceipts(List<EventReceipt> saved, List<AgentPlanEvent> currentEvents) {
        var current = currentEvents.stream().filter(event -> event.getId() != null)
                .collect(java.util.stream.Collectors.toMap(AgentPlanEvent::getId, event -> event));
        for (EventReceipt receipt : saved) {
            AgentPlanEvent event = current.get(receipt.eventId());
            String payloadHash = event == null ? null
                    : sha256(event.getPayloadJson() == null ? "" : event.getPayloadJson());
            if (event == null || !receipt.eventType().equals(event.getEventType())
                    || !java.util.Objects.equals(receipt.stepId(), event.getStepId())
                    || !java.util.Objects.equals(receipt.idempotencyKey(), event.getIdempotencyKey())
                    || !receipt.payloadHash().equals(payloadHash)) {
                throw new IllegalStateException("durable Plan tool or Evidence receipt was changed after checkpoint");
            }
        }
    }

    private AgentPlan ownedPlan(AgentPlanExecutionLease lease) {
        leases.assertOwned(lease);
        return plans.findByIdAndUserId(lease.planId(), lease.userId())
                .orElseThrow(() -> new IllegalStateException("durable Plan no longer exists"));
    }

    private List<AgentPlanEvent> safeEvents(Long planId) {
        List<AgentPlanEvent> found = events.findByPlanIdOrderByCreatedAtAsc(planId);
        return found == null ? List.of() : found;
    }

    private List<ManifestItem> manifest(ProjectManifestResponse manifest) {
        return manifest.files().stream().sorted(Comparator.comparing(ProjectFileEntry::path))
                .map(this::manifestItem)
                .toList();
    }

    private ManifestItem manifestItem(ProjectFileEntry file) {
        if (file == null || !StringUtils.hasText(file.path()) || file.path().contains("\\")
                || file.sizeBytes() < 0 || !StringUtils.hasText(file.sha256())
                || !file.sha256().matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("durable Plan manifest entry is invalid");
        }
        Path relative;
        try {
            relative = Path.of(file.path());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("durable Plan manifest path is invalid", exception);
        }
        if (relative.isAbsolute() || file.path().matches("(?i)^[a-z]:/.*")
                || relative.getNameCount() == 0 || !relative.normalize().equals(relative)) {
            throw new IllegalStateException("durable Plan checkpoint cannot store an absolute or unsafe path");
        }
        return new ManifestItem(file.path(), file.sizeBytes(), file.sha256().toLowerCase());
    }

    private String planDigest(AgentPlan plan, List<AgentPlanStep> planSteps) {
        StringBuilder source = new StringBuilder()
                .append(plan.getId()).append('|').append(plan.getUserId()).append('|')
                .append(plan.getSessionId()).append('|').append(nullToEmpty(plan.getGoal())).append('|')
                .append(nullToEmpty(plan.getSummary())).append('|').append(plan.getRagDisabled()).append('|')
                .append(plan.getSkillId()).append('|').append(sha256(nullToEmpty(plan.getRawPlanJson())));
        planSteps.stream().sorted(Comparator.comparing(AgentPlanStep::getSortOrder)).forEach(step -> source
                .append('\n').append(step.getId()).append('|').append(step.getStepKey()).append('|')
                .append(step.getSortOrder()).append('|').append(nullToEmpty(step.getDescription())).append('|')
                .append(nullToEmpty(step.getType())).append('|').append(nullToEmpty(step.getDependenciesJson())).append('|')
                .append(nullToEmpty(step.getAllowedToolsJson())).append('|').append(nullToEmpty(step.getSuccessCriteria())));
        return sha256(source.toString());
    }

    private List<String> persistedTools(List<AgentPlanStep> planSteps) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        for (AgentPlanStep step : planSteps) {
            try {
                JsonNode value = json.readTree(step.getAllowedToolsJson());
                if (value != null && value.isArray()) value.forEach(item -> {
                    if (item.isTextual() && StringUtils.hasText(item.textValue())) tools.add(item.textValue());
                });
            } catch (Exception exception) {
                throw new IllegalStateException("durable Plan allowedTools are invalid", exception);
            }
        }
        return tools.stream().sorted().toList();
    }

    private int consumedAttempts(List<AgentPlanStep> planSteps) {
        return planSteps.stream().map(AgentPlanStep::getAttemptCount).filter(java.util.Objects::nonNull)
                .mapToInt(value -> Math.max(0, value)).sum();
    }

    private int consumedToolCalls(List<AgentPlanEvent> planEvents) {
        Set<String> calls = new LinkedHashSet<>();
        for (AgentPlanEvent event : planEvents) {
            if (!"step_tool_observation".equals(event.getEventType())) continue;
            try {
                JsonNode payload = json.readTree(event.getPayloadJson());
                if (payload.has("budgetConsumed") && !payload.path("budgetConsumed").asBoolean(false)) continue;
                calls.add(StringUtils.hasText(event.getIdempotencyKey())
                        ? event.getIdempotencyKey() : "event:" + event.getId());
            } catch (Exception exception) {
                throw new IllegalStateException("durable Plan tool receipt is invalid", exception);
            }
        }
        return calls.size();
    }

    private int effectiveMaxDuplicateToolCalls(ResolvedToolPolicy policy, BudgetCeiling ceiling) {
        if (policy == null) return 0;
        return Math.min(Math.max(0, policy.maxDuplicateToolCalls()), ceiling.maxDuplicateToolCalls());
    }

    private String writeCanonical(Checkpoint checkpoint) {
        try {
            return json.writeValueAsString(sort(json.valueToTree(checkpoint)));
        } catch (Exception exception) {
            throw new IllegalStateException("durable Plan checkpoint serialization failed", exception);
        }
    }

    private JsonNode sort(JsonNode value) {
        if (value == null || value.isValueNode()) return value;
        if (value.isArray()) {
            ArrayNode sorted = json.createArrayNode();
            value.forEach(item -> sorted.add(sort(item)));
            return sorted;
        }
        ObjectNode sorted = json.createObjectNode();
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        names.stream().sorted().forEach(name -> sorted.set(name, sort(value.get(name))));
        return sorted;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(nullToEmpty(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void requireArrayObjects(JsonNode array, Set<String> fields, String label) {
        if (!array.isArray()) throw new IllegalStateException(label + " must be an array");
        array.forEach(value -> requireFields(value, fields, label + " item"));
    }

    private void requireFields(JsonNode object, Set<String> fields, String label) {
        if (!object.isObject()) throw new IllegalStateException(label + " must be an object");
        Set<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw new IllegalStateException(label + " fields are invalid");
    }

    public record BudgetCeiling(int maxPlanSeconds, int maxStepAttempts,
                                int maxDuplicateToolCalls, int maxToolCalls) {
        public BudgetCeiling {
            if (maxPlanSeconds < 1 || maxStepAttempts < 1 || maxDuplicateToolCalls < 0 || maxToolCalls < 0) {
                throw new IllegalArgumentException("checkpoint budget ceiling is invalid");
            }
        }
    }

    public record Validation(boolean recovery, long checkpointVersion,
                             ProjectRuntimeContext projectContext, ProjectManifestResponse manifest,
                             BudgetCeiling budgetCeiling) { }

    public record Checkpoint(String schema, long version, long planId, long userId, long sessionId,
                             long projectId, String projectVersion, List<ManifestItem> manifest,
                             String planDigest, List<String> allowedTools, int maxPlanSeconds,
                             int maxStepAttempts, int maxDuplicateToolCalls, int maxToolCallsPerStep,
                             int maxToolCalls,
                             int consumedAttempts, int consumedToolCalls, List<StepState> steps,
                             List<EventReceipt> receipts, List<String> remainingStepKeys,
                             boolean cancelled) { }

    public record ManifestItem(String relativePath, long sizeBytes, String sha256) { }
    public record StepState(Long id, String stepKey, String status, int attemptCount, String resultHash) { }
    public record EventReceipt(Long eventId, Long stepId, String eventType,
                               String idempotencyKey, String payloadHash) { }
}
