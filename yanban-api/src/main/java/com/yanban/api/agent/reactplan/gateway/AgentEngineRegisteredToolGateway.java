package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentToolPolicyEngine;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEngineRegisteredToolGateway {
    private static final int MAX_RESULT_BYTES = 1_000_000;
    private final ObjectMapper json;
    private final ToolRegistry registry;
    private final AgentToolPolicyEngine policies;
    private final AgentTurnProductContextResolver contexts;

    AgentEngineRegisteredToolGateway(
            ObjectMapper json,
            ToolRegistry registry,
            AgentToolPolicyEngine policies,
            AgentTurnProductContextResolver contexts) {
        this.json = json;
        this.registry = registry;
        this.policies = policies;
        this.contexts = contexts;
    }

    RegisteredToolCatalog catalog(EngineTaskAuthority authority) {
        requireCurrent(authority);
        List<RegisteredToolSpec> tools = definitions(authority).stream()
                .map(definition -> new RegisteredToolSpec("function",
                        new RegisteredToolFunction(definition.name(),
                                definition.description(), definition.parameters())))
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
        ToolResult result;
        try {
            ToolExecutionContext.setCurrentUserId(authority.userId());
            ToolExecutionContext.setCurrentProjectId(authority.projectId());
            ToolExecutionContext.setResolvedAllowedTools(allowed);
            result = registry.execute(new ToolCall(
                    request.callId(), request.toolName(), request.arguments()), allowed);
        } catch (RuntimeException failure) {
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
        return new RegisteredToolResult(
                "1.0", request.callId(), request.toolName(), request.requestDigest(),
                result.success(), output,
                result.errorCode() == null ? null : result.errorCode().name(),
                bounded(result.errorMessage(), 2_000), result.retryable(),
                result.evidenceRefs(), result.version());
    }

    private List<ToolDefinition> definitions(EngineTaskAuthority authority) {
        Set<String> policyNames = Set.copyOf(
                policies.decideProject(null, null).allowedTools());
        return registry.listDefinitions().stream()
                .filter(definition -> policyNames.contains(definition.name()))
                .filter(definition -> registry.findDescriptor(definition.name())
                        .map(AgentEngineRegisteredToolGateway::readOnly)
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

    private static String bounded(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
