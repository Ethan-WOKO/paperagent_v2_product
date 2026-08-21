package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentToolPolicyEngine;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.ReactPlanHistoryToolContract;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.RegisteredToolCall;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.RegisteredToolCatalog;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.RegisteredToolFunction;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.RegisteredToolResult;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.RegisteredToolSpec;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEngineRegisteredToolGateway {
    private static final int MAX_RESULT_BYTES = 1_000_000;
    private static final Set<String> SYNCHRONOUS_RETRIEVAL_TOOLS = Set.of(
            "search_web", "search_knowledge", "recommend_literature");
    private static final Set<String> LITERATURE_TASK_TOOLS = Set.of(
            "literature_search_start", "literature_search_status",
            "literature_search_result", "literature_search_cancel");
    private static final Set<String> PAPER_TASK_READ_TOOLS = Set.of(
            "paper_polish_status", "paper_polish_result");
    private static final Set<String> HISTORY_TOOLS =
            ReactPlanHistoryToolContract.TOOL_NAMES;
    private final ObjectMapper json;
    private final ToolRegistry registry;
    private final AgentToolPolicyEngine policies;
    private final AgentTurnProductContextResolver contexts;
    private final AgentEngineRegisteredToolTransactions transactions;

    @Autowired
    AgentEngineRegisteredToolGateway(
            ObjectMapper json,
            ToolRegistry registry,
            AgentToolPolicyEngine policies,
            AgentTurnProductContextResolver contexts,
            AgentEngineRegisteredToolTransactions transactions) {
        this.json = json;
        this.registry = registry;
        this.policies = policies;
        this.contexts = contexts;
        this.transactions = transactions;
    }

    AgentEngineRegisteredToolGateway(
            ObjectMapper json,
            ToolRegistry registry,
            AgentToolPolicyEngine policies,
            AgentTurnProductContextResolver contexts) {
        this(json, registry, policies, contexts, null);
    }

    RegisteredToolCatalog catalog(EngineTaskAuthority authority) {
        requireCurrent(authority);
        List<RegisteredToolSpec> tools = definitions(authority).stream()
                .map(this::modelTool)
                .toList();
        String digest = ReactPlanCanonicalJson.digest(json, tools);
        return new RegisteredToolCatalog("1.0", authority.taskId(),
                authority.projectVersion(), digest, tools);
    }

    RegisteredToolResult invoke(
            EngineTaskAuthority authority, RegisteredToolCall request) {
        validate(request);
        requireCurrent(authority);
        Set<String> allowed = definitions(authority).stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toUnmodifiableSet());
        if (!allowed.contains(request.toolName())) {
            throw EngineGatewayException.forbidden("REGISTERED_TOOL_NOT_ALLOWED");
        }
        String expectedDigest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", request.toolName(), "arguments", request.arguments()));
        if (!expectedDigest.equals(request.requestDigest())) {
            throw EngineGatewayException.badRequest("REGISTERED_TOOL_DIGEST_INVALID");
        }
        ToolDescriptor descriptor = registry.findDescriptor(request.toolName())
                .orElseThrow(() -> EngineGatewayException.forbidden(
                        "REGISTERED_TOOL_NOT_ALLOWED"));
        if (transactions != null) {
            var replay = transactions.claim(
                    authority.taskId(), request.callId(), request.toolName(),
                    request.requestDigest(), safelyReplayable(descriptor));
            if (replay.isPresent()) return readResult(replay.orElseThrow());
        }
        JsonNode executionArguments = executionArguments(authority, request);
        ToolResult result;
        try {
            ToolExecutionContext.setCurrentUserId(authority.userId());
            ToolExecutionContext.setCurrentProjectId(authority.projectId());
            ToolExecutionContext.setResolvedAllowedTools(allowed);
            result = registry.execute(new ToolCall(request.callId(), request.toolName(),
                    executionArguments), allowed);
        } catch (RuntimeException failure) {
            if (transactions != null) transactions.fail(
                    authority.taskId(), request.callId(),
                    "REGISTERED_TOOL_EXECUTION_REJECTED");
            throw EngineGatewayException.badRequest("REGISTERED_TOOL_EXECUTION_REJECTED");
        } finally {
            ToolExecutionContext.clear();
        }
        requireCurrent(authority);
        JsonNode output = result.output();
        if (output != null
                && output.hasNonNull("projectVersion")
                && !authority.projectVersion().equals(output.path("projectVersion").asText())) {
            throw EngineGatewayException.conflict("REGISTERED_TOOL_PROJECT_VERSION_CHANGED");
        }
        try {
            if (output != null && json.writeValueAsBytes(output).length > MAX_RESULT_BYTES) {
                throw EngineGatewayException.tooLarge("REGISTERED_TOOL_RESULT_TOO_LARGE");
            }
        } catch (EngineGatewayException failure) {
            throw failure;
        } catch (Exception impossible) {
            throw EngineGatewayException.conflict("REGISTERED_TOOL_RESULT_INVALID");
        }
        RegisteredToolResult response = new RegisteredToolResult(
                "1.0", request.callId(), request.toolName(), request.requestDigest(),
                result.success(), output,
                result.errorCode() == null ? null : result.errorCode().name(),
                bounded(result.errorMessage(), 2_000), result.retryable(),
                result.evidenceRefs(), result.version());
        if (transactions != null) transactions.complete(
                authority.taskId(), request.callId(), writeResult(response));
        return response;
    }

    private List<ToolDefinition> definitions(EngineTaskAuthority authority) {
        Set<String> policyNames = new LinkedHashSet<>(
                policies.decideProject(null, null).allowedTools());
        policyNames.addAll(SYNCHRONOUS_RETRIEVAL_TOOLS);
        policyNames.addAll(LITERATURE_TASK_TOOLS);
        policyNames.addAll(PAPER_TASK_READ_TOOLS);
        policyNames.addAll(HISTORY_TOOLS);
        return registry.listDefinitions().stream()
                .filter(definition -> policyNames.contains(definition.name())
                        || isMcpTool(definition.name()))
                .filter(definition -> registry.findDescriptor(definition.name())
                        .map(descriptor -> eligible(definition.name(), descriptor))
                        .orElse(false))
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    private void requireCurrent(EngineTaskAuthority authority) {
        VerifiedAgentTurnProductContext context;
        try {
            context = contexts.resolve(authority.userId(), authority.turnId());
        } catch (RuntimeException failure) {
            throw EngineGatewayException.forbidden("TASK_AUTHORITY_REJECTED");
        }
        if (!Long.valueOf(authority.sessionId()).equals(context.identity().sessionId())
                || !Long.valueOf(authority.projectId()).equals(context.identity().projectId())
                || context.projectVersionId().isEmpty()
                || !authority.projectVersion().equals(context.projectVersionId().orElseThrow())) {
            throw EngineGatewayException.conflict("TASK_PROJECT_VERSION_CHANGED");
        }
    }

    private void validate(RegisteredToolCall request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.callId() == null
                || !request.callId().matches("call\\.[a-f0-9]{40}")
                || request.toolName() == null
                || !request.toolName().matches("[a-z][a-z0-9_]{0,63}")
                || request.arguments() == null || !request.arguments().isObject()
                || request.requestDigest() == null
                || !request.requestDigest().matches("[a-f0-9]{64}")) {
            throw EngineGatewayException.badRequest("REGISTERED_TOOL_CALL_INVALID");
        }
    }

    private static boolean readOnly(ToolDescriptor descriptor) {
        return descriptor.modelVisible()
                && descriptor.supportedProfiles().contains(
                        ToolDescriptor.CapabilityProfile.PROJECT)
                && (descriptor.sideEffectType() == ToolDescriptor.SideEffectType.NONE
                || descriptor.sideEffectType() == ToolDescriptor.SideEffectType.READ_ONLY)
                && descriptor.confirmationPolicy()
                == ToolDescriptor.ConfirmationPolicy.NEVER;
    }

    private static boolean isMcpTool(String name) {
        return name != null && (name.startsWith("mcp_github__")
                || name.startsWith("mcp_fs__"));
    }

    private static boolean safelyReplayable(ToolDescriptor descriptor) {
        return descriptor.sideEffectType() == ToolDescriptor.SideEffectType.NONE
                || descriptor.sideEffectType() == ToolDescriptor.SideEffectType.READ_ONLY
                || descriptor.sideEffectType() == ToolDescriptor.SideEffectType.EXTERNAL_READ
                || descriptor.idempotencyPolicy()
                == ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY;
    }

    private RegisteredToolResult readResult(String value) {
        try {
            return json.readValue(value, RegisteredToolResult.class);
        } catch (Exception corrupt) {
            throw EngineGatewayException.conflict("REGISTERED_TOOL_REPLAY_INVALID");
        }
    }

    private String writeResult(RegisteredToolResult value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception impossible) {
            throw EngineGatewayException.conflict("REGISTERED_TOOL_RESULT_INVALID");
        }
    }

    private static boolean eligible(String name, ToolDescriptor descriptor) {
        if (isMcpTool(name)) {
            return descriptor.modelVisible()
                    && descriptor.supportedProfiles().contains(
                            ToolDescriptor.CapabilityProfile.PROJECT)
                    && descriptor.requiredPermissions().equals(List.of("mcp:read"))
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.EXTERNAL))
                    && (descriptor.sideEffectType()
                            == ToolDescriptor.SideEffectType.READ_ONLY
                    || descriptor.sideEffectType()
                            == ToolDescriptor.SideEffectType.EXTERNAL_READ)
                    && descriptor.confirmationPolicy()
                            == ToolDescriptor.ConfirmationPolicy.NEVER
                    && descriptor.asyncMode() == ToolDescriptor.AsyncMode.SYNC;
        }
        if (HISTORY_TOOLS.contains(name)) {
            return eligibleHistory(name, descriptor);
        }
        if (PAPER_TASK_READ_TOOLS.contains(name)) {
            return descriptor.modelVisible()
                    && descriptor.supportedProfiles().contains(
                            ToolDescriptor.CapabilityProfile.PROJECT)
                    && descriptor.sideEffectType() == ToolDescriptor.SideEffectType.NONE
                    && descriptor.asyncMode() == ToolDescriptor.AsyncMode.SYNC
                    && descriptor.confirmationPolicy()
                    == ToolDescriptor.ConfirmationPolicy.NEVER
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.SESSION));
        }
        if (LITERATURE_TASK_TOOLS.contains(name)) {
            return eligibleLiteratureTask(name, descriptor);
        }
        if (!SYNCHRONOUS_RETRIEVAL_TOOLS.contains(name)) {
            return readOnly(descriptor);
        }
        if (!descriptor.modelVisible()
                || !descriptor.supportedProfiles().contains(
                        ToolDescriptor.CapabilityProfile.PROJECT)
                || descriptor.confirmationPolicy()
                != ToolDescriptor.ConfirmationPolicy.NEVER
                || descriptor.asyncMode() != ToolDescriptor.AsyncMode.SYNC) {
            return false;
        }
        return switch (name) {
            case "search_web" -> descriptor.sideEffectType()
                    == ToolDescriptor.SideEffectType.EXTERNAL_READ
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.EXTERNAL));
            case "search_knowledge" -> descriptor.sideEffectType()
                    == ToolDescriptor.SideEffectType.NONE
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.USER_KNOWLEDGE));
            case "recommend_literature" -> descriptor.sideEffectType()
                    == ToolDescriptor.SideEffectType.CREATE
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.EXTERNAL,
                            ToolDescriptor.ResourceScope.SESSION));
            default -> false;
        };
    }

    private static boolean eligibleHistory(String name, ToolDescriptor descriptor) {
        return descriptor.name().equals(name)
                && descriptor.version().equals("history-v1")
                && descriptor.modelVisible()
                && descriptor.capabilityDomain().equals("conversation-history")
                && descriptor.supportedProfiles().equals(List.of(
                        ToolDescriptor.CapabilityProfile.PROJECT))
                && descriptor.requiredPermissions().equals(List.of("project:read"))
                && descriptor.resourceScopes().equals(List.of(
                        ToolDescriptor.ResourceScope.SESSION,
                        ToolDescriptor.ResourceScope.PROJECT))
                && descriptor.sideEffectType() == ToolDescriptor.SideEffectType.NONE
                && descriptor.confirmationPolicy()
                        == ToolDescriptor.ConfirmationPolicy.NEVER
                && descriptor.asyncMode() == ToolDescriptor.AsyncMode.SYNC
                && descriptor.idempotencyPolicy()
                        == ToolDescriptor.IdempotencyPolicy.NONE
                && descriptor.repeatPolicy()
                        == ToolDescriptor.RepeatPolicy.ALLOW_LIMITED;
    }

    private static boolean eligibleLiteratureTask(
            String name, ToolDescriptor descriptor) {
        if (!descriptor.modelVisible()
                || !descriptor.supportedProfiles().contains(
                        ToolDescriptor.CapabilityProfile.PROJECT)
                || descriptor.confirmationPolicy()
                != ToolDescriptor.ConfirmationPolicy.NEVER) {
            return false;
        }
        return switch (name) {
            case "literature_search_start" -> descriptor.sideEffectType()
                    == ToolDescriptor.SideEffectType.CREATE
                    && descriptor.asyncMode() == ToolDescriptor.AsyncMode.EXTERNAL_TASK
                    && descriptor.idempotencyPolicy()
                    == ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.EXTERNAL,
                            ToolDescriptor.ResourceScope.SESSION,
                            ToolDescriptor.ResourceScope.PROJECT));
            case "literature_search_status", "literature_search_result" ->
                    descriptor.sideEffectType() == ToolDescriptor.SideEffectType.NONE
                    && descriptor.asyncMode() == ToolDescriptor.AsyncMode.SYNC
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.SESSION));
            case "literature_search_cancel" -> descriptor.sideEffectType()
                    == ToolDescriptor.SideEffectType.MODIFY
                    && descriptor.asyncMode() == ToolDescriptor.AsyncMode.SYNC
                    && descriptor.resourceScopes().equals(List.of(
                            ToolDescriptor.ResourceScope.SESSION));
            default -> false;
        };
    }

    private RegisteredToolSpec modelTool(ToolDefinition definition) {
        JsonNode parameters = definition.parameters();
        if ("literature_search_start".equals(definition.name())) {
            ObjectNode sanitized = parameters.deepCopy();
            JsonNode properties = sanitized.path("properties");
            if (properties instanceof ObjectNode object) {
                object.remove(List.of("clientRequestId", "projectId"));
            }
            parameters = sanitized;
        }
        return new RegisteredToolSpec("function", new RegisteredToolFunction(
                definition.name(), definition.description(), parameters));
    }

    private JsonNode executionArguments(
            EngineTaskAuthority authority, RegisteredToolCall request) {
        if (HISTORY_TOOLS.contains(request.toolName())) {
            for (String serverArgument : ReactPlanHistoryToolContract.SERVER_ARGUMENTS) {
                if (request.arguments().has(serverArgument)) {
                    throw EngineGatewayException.badRequest(
                            "REGISTERED_TOOL_SERVER_ARGUMENT_FORBIDDEN");
                }
            }
            ObjectNode enriched = request.arguments().deepCopy();
            enriched.put(ReactPlanHistoryToolContract.SERVER_TASK_ID, authority.taskId());
            enriched.put(ReactPlanHistoryToolContract.SERVER_SESSION_ID, authority.sessionId());
            enriched.put(ReactPlanHistoryToolContract.SERVER_PROJECT_ID, authority.projectId());
            return enriched;
        }
        if (!"literature_search_start".equals(request.toolName())) {
            return request.arguments();
        }
        if (request.arguments().has("clientRequestId")
                || request.arguments().has("projectId")) {
            throw EngineGatewayException.badRequest(
                    "REGISTERED_TOOL_SERVER_ARGUMENT_FORBIDDEN");
        }
        ObjectNode enriched = request.arguments().deepCopy();
        enriched.put("clientRequestId", "agent-engine-" + request.callId().substring(5));
        enriched.put("projectId", authority.projectId());
        return enriched;
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
