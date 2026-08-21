package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.reactplan.gateway.AgentEngineTaskGrantService;
import com.yanban.api.agent.reactplan.gateway.EngineModelRouteCandidate;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.memory.LongTermMemoryRetrievalService;
import com.yanban.api.memory.LongTermMemorySnapshot;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.ResolvedSkill;
import com.yanban.api.skills.SkillsService;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
final class ReactPlanRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(ReactPlanRuntimeService.class);

    private final ObjectMapper json;
    private final AgentTurnProductContextResolver contexts;
    private final AuthenticatedReactPlanBootstrapComposer plans;
    private final AgentEngineTaskGrantService grants;
    private final ReactPlanEngineClient engine;
    private final LongTermMemoryRetrievalService longTermMemories;
    private final UserSettingsService settings;
    private final ReactPlanConversationContextService conversations;
    private final ReactPlanConversationSummaryQueue conversationSummaries;
    private final SkillsService skills;

    @Autowired
    ReactPlanRuntimeService(
            ObjectMapper json,
            AgentTurnProductContextResolver contexts,
            AuthenticatedReactPlanBootstrapComposer plans,
            AgentEngineTaskGrantService grants,
            ReactPlanEngineClient engine,
            LongTermMemoryRetrievalService longTermMemories,
            UserSettingsService settings,
            ReactPlanConversationContextService conversations,
            ReactPlanConversationSummaryQueue conversationSummaries,
            SkillsService skills) {
        this.json = json;
        this.contexts = contexts;
        this.plans = plans;
        this.grants = grants;
        this.engine = engine;
        this.longTermMemories = longTermMemories;
        this.settings = settings;
        this.conversations = conversations;
        this.conversationSummaries = conversationSummaries;
        this.skills = skills;
    }

    ReactPlanRuntimeService(
            ObjectMapper json,
            AgentTurnProductContextResolver contexts,
            AuthenticatedReactPlanBootstrapComposer plans,
            AgentEngineTaskGrantService grants,
            ReactPlanEngineClient engine,
            LongTermMemoryRetrievalService longTermMemories,
            UserSettingsService settings,
            ReactPlanConversationContextService conversations,
            ReactPlanConversationSummaryQueue conversationSummaries) {
        this(json, contexts, plans, grants, engine, longTermMemories, settings,
                conversations, conversationSummaries, null);
    }

    JsonNode submit(long userId, long turnId, ReactPlanTaskRequest request) {
        VerifiedAgentTurnProductContext context = projectContext(userId, turnId);
        String taskId = taskId(userId, turnId);
        UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                userId, request.provider(), request.model());
        String provider = endpoint.providerKey();
        String model = endpoint.modelName();
        List<EngineModelRouteCandidate> modelFallbacks = modelFallbacks(
                userId, provider, model);
        conversationSummaries.catchUp(userId, context.identity().sessionId());
        Map<String, Object> authority = authority(
                context, request.instruction(), provider, model,
                modelFallbacks, skillSnapshot(userId, request.skillId()));
        String requestDigest = ReactPlanCanonicalJson.digest(json, authority);

        PersistenceResult<?> persisted = plans.bootstrap(
                userId, turnId, planCommand(taskId, request.instruction()));
        if (!persisted.successful()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The authenticated Turn is bound to another Plan request");
        }
        EngineTaskGrant grant = grants.issue(
                taskId, requestDigest, userId, turnId, provider, model,
                modelFallbacks);
        ObjectNode submission = json.createObjectNode();
        submission.put("contractVersion", "1.0");
        submission.put("taskId", taskId);
        submission.put("requestDigest", requestDigest);
        submission.set("authority", json.valueToTree(authority));
        submission.set("context", contextEnvelope(
                conversations.envelope(userId, context.identity().sessionId()),
                memorySnapshot(
                userId,
                context.identity().projectId(),
                context.projectVersionId().orElseThrow(),
                taskId)));
        ObjectNode gateway = submission.putObject("gateway");
        gateway.put("taskGrant", grant.value());
        gateway.put("expiresAt", grant.expiresAt().toString());
        return engine.submit(submission);
    }

    private JsonNode contextEnvelope(JsonNode historicalContext, LongTermMemorySnapshot snapshot) {
        ObjectNode result = json.createObjectNode();
        result.set("historicalContext", historicalContext);
        ObjectNode memory = result.putObject("longTermMemory");
        memory.put("schemaVersion", "1.0");
        memory.put("type", "long_term_memory");
        memory.put("notAnInstruction", true);
        ObjectNode usage = memory.putObject("usage");
        usage.put("currentTaskHasPriority", true);
        usage.put("mayGuidePreferences", true);
        usage.put("cannotGrantAuthority", true);
        memory.set("entries", json.valueToTree(snapshot.entries()));
        return result;
    }

    private LongTermMemorySnapshot memorySnapshot(
            long userId, Long projectId, String projectVersion, String taskId) {
        try {
            LongTermMemorySnapshot snapshot = longTermMemories.retrieveAllGoverned(
                    userId, projectId, projectVersion);
            log.debug("Loaded {} governed long-term memories for ReAct task {}",
                    snapshot.entries().size(), taskId);
            return snapshot;
        } catch (RuntimeException failure) {
            log.warn("Long-term memory loading failed for ReAct task {}; continuing without memory: {}",
                    taskId, failure.getClass().getSimpleName());
            return LongTermMemorySnapshot.empty();
        }
    }

    JsonNode task(long userId, long turnId, String taskId) {
        requireTask(userId, turnId, taskId);
        return engine.task(taskId);
    }

    JsonNode cancel(long userId, long turnId, String taskId, String clientRequestId) {
        requireTask(userId, turnId, taskId);
        if (clientRequestId == null || !clientRequestId.matches("cancel\\.[A-Za-z0-9_-]{16,120}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cancel request id is invalid");
        }
        return engine.cancel(taskId, clientRequestId);
    }

    JsonNode answer(long userId, long turnId, String taskId, ReactPlanAnswerRequest request) {
        requireTask(userId, turnId, taskId);
        String answerDigest = sha256(request.answer());
        ObjectNode body = json.createObjectNode();
        body.put("contractVersion", "1.0");
        body.put("clientRequestId", "answer."
                + sha256(taskId + "\0" + request.questionId()).substring(0, 40));
        body.put("questionId", request.questionId());
        body.put("answer", request.answer());
        body.put("answerDigest", answerDigest);
        return engine.answer(taskId, body);
    }

    InputStream events(long userId, long turnId, String taskId, long afterSequence) {
        requireTask(userId, turnId, taskId);
        if (afterSequence < 0) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Last-Event-ID must not be negative");
        return engine.events(taskId, afterSequence);
    }

    void requireTask(long userId, long turnId, String taskId) {
        projectContext(userId, turnId);
        if (!taskId(userId, turnId).equals(taskId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ReAct task not found");
        }
    }

    private VerifiedAgentTurnProductContext projectContext(long userId, long turnId) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        if (context.identity().projectId() == null || context.projectVersionId().isEmpty()
                || !context.projectVersionId().orElseThrow().matches("[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ReAct P1 requires a Project-scoped Turn with a frozen content version");
        }
        return context;
    }

    private static Map<String, Object> authority(
            VerifiedAgentTurnProductContext context,
            String instruction,
            String provider,
            String model,
            List<EngineModelRouteCandidate> modelFallbacks,
            Map<String, Object> skill) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("projectId", String.valueOf(context.identity().projectId()));
        project.put("projectVersion", context.projectVersionId().orElseThrow());
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("readProject", true);
        permissions.put("writeWorkspace", true);
        permissions.put("executeSandbox", true);
        Map<String, Object> selectedModel = new LinkedHashMap<>();
        selectedModel.put("provider", ReactPlanValues.text(provider, "provider"));
        selectedModel.put("model", ReactPlanValues.text(model, "model"));
        selectedModel.put("fallbacks", modelFallbacks.stream().map(candidate -> Map.of(
                "provider", candidate.provider(), "model", candidate.model())).toList());
        Map<String, Object> authority = new LinkedHashMap<>();
        authority.put("runMode", "PERSISTENT_PLAN_EXECUTE");
        authority.put("sessionRef", "session." + context.identity().sessionId());
        authority.put("project", project);
        authority.put("instruction", instruction);
        authority.put("permissions", permissions);
        authority.put("model", selectedModel);
        if (skill != null) authority.put("skill", skill);
        return authority;
    }

    private List<EngineModelRouteCandidate> modelFallbacks(
            long userId, String primaryProvider, String primaryModel) {
        return settings.configuredModelReferences(userId).stream()
                .filter(reference -> !reference.providerKey().equals(primaryProvider)
                        || !reference.modelName().equals(primaryModel))
                .map(reference -> new EngineModelRouteCandidate(
                        reference.providerKey(), reference.modelName()))
                .distinct()
                .limit(7)
                .toList();
    }

    private Map<String, Object> skillSnapshot(long userId, String skillId) {
        if (skillId == null) return null;
        if (skills == null) throw new IllegalStateException("Skills service is unavailable");
        ResolvedSkill resolved = skills.resolveEnabledSkill(userId, skillId);
        List<String> allowedTools = resolved.allowedTools().stream().sorted().toList();
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("id", resolved.id());
        semantics.put("prompt", resolved.prompt());
        semantics.put("allowedTools", allowedTools);
        Map<String, Object> snapshot = new LinkedHashMap<>(semantics);
        snapshot.put("digest", ReactPlanCanonicalJson.digest(json, semantics));
        return snapshot;
    }

    private static ReactPlanBootstrapCommand planCommand(
            String taskId, String instruction) {
        RoutingDecision route = new RoutingDecision(
                new RoutingRequestId("route-" + taskId.substring("task.".length(), 38)),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.PROJECT_FILE_ACCESS,
                        RoutingRequirement.TOOL_USE,
                        RoutingRequirement.EXECUTION));
        ExecutionProfile profile = new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE,
                        Capability.EXECUTE_COMMAND),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(6), Duration.ofMinutes(5),
                        512L * 1024 * 1024, 1024L * 1024, 4),
                Set.of());
        Instant frozen = Instant.EPOCH.plusSeconds(
                Long.parseUnsignedLong(taskId.substring(5, 20), 16) & 0x7fff_ffffL);
        return new ReactPlanBootstrapCommand(
                route,
                new TaskFrameDraft(
                        instruction,
                        List.of("authenticated frozen ProjectVersion"),
                        List.of("receipt-backed answer"),
                        List.of("modify isolated Workspace only", "no direct Project modification")),
                profile,
                new BoundedExecutionHints(20, Duration.ofMinutes(6)),
                frozen, frozen.plusMillis(1), frozen.plusMillis(2));
    }

    static String taskId(long userId, long turnId) {
        return "task." + sha256("reactplan-task-v1\0" + userId + "\0" + turnId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
