package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.ReactPlanTraceIds;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ModelCompletionRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ModelCompletionResult;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ModelToolCall;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ModelUsage;
import com.yanban.api.quota.UserQuotaService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ModelProviderException;
import com.yanban.core.model.ToolCall;
import com.yanban.core.model.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEngineModelGateway {
    private static final Logger log = LoggerFactory.getLogger(AgentEngineModelGateway.class);
    private static final int MAX_MESSAGES = 120;
    private static final int MAX_TOOLS = 80;
    private static final int MAX_TOTAL_CHARACTERS = 300_000;
    private final ObjectMapper json;
    private final UserSettingsService settings;
    private final UserQuotaService quotas;
    private final ChatModelProvider models;
    private final AgentEngineModelCompletionTransactions transactions;

    AgentEngineModelGateway(ObjectMapper json, UserSettingsService settings, UserQuotaService quotas,
                            @Qualifier("chatModelProvider") ChatModelProvider models,
                            AgentEngineModelCompletionTransactions transactions) {
        this.json = json; this.settings = settings; this.quotas = quotas;
        this.models = models; this.transactions = transactions;
    }

    ModelCompletionResult complete(EngineTaskAuthority authority, ModelCompletionRequest request) {
        long startedNanos = System.nanoTime();
        validate(authority, request);
        String semanticDigest = ReactPlanCanonicalJson.digest(json, Map.of(
                "contractVersion", request.contractVersion(),
                "clientRequestId", request.clientRequestId(),
                "provider", request.provider(), "model", request.model(),
                "messages", request.messages().stream().map(message -> {
                    Map<String, Object> value = new java.util.LinkedHashMap<>();
                    value.put("role", message.role());
                    value.put("content", message.content());
                    if (message.toolCallId() != null) value.put("toolCallId", message.toolCallId());
                    if (message.toolCalls() != null) value.put("toolCalls", message.toolCalls());
                    return value;
                }).toList(), "tools", request.tools(),
                "maxOutputTokens", request.maxOutputTokens()));
        if (!semanticDigest.equals(request.requestDigest())) {
            throw EngineGatewayException.badRequest("MODEL_REQUEST_DIGEST_INVALID");
        }
        long requestBytes = write(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        var replay = transactions.claim(authority.taskId(), request.clientRequestId(), request.requestDigest(),
                request.provider(), request.model(), requestBytes);
        if (replay.isPresent()) {
            observe(authority, request, "replayed", null, startedNanos, 0, 0);
            return replay(replay.orElseThrow(), authority);
        }
        try {
            quotas.assertCanUseAi(authority.userId());
            List<EngineModelRouteCandidate> routes = new ArrayList<>();
            routes.add(new EngineModelRouteCandidate(
                    authority.modelProvider(), authority.modelName()));
            routes.addAll(authority.modelFallbacks());
            ChatResponse response = null;
            EngineModelRouteCandidate resolvedRoute = null;
            for (EngineModelRouteCandidate route : routes) {
                try {
                    UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                            authority.userId(), route.provider(), route.model());
                    if (!endpoint.providerKey().equals(route.provider())
                            || !endpoint.modelName().equals(route.model())) {
                        log.warn("reactplan_model_route_changed taskId={} provider={} model={}",
                                authority.taskId(), route.provider(), route.model());
                        continue;
                    }
                    ChatResponse candidateResponse = callModel(authority, request, endpoint);
                    if (candidateResponse == null || candidateResponse.message() == null) {
                        throw new ModelProviderException("Model returned an invalid response");
                    }
                    response = candidateResponse;
                    resolvedRoute = route;
                    break;
                } catch (ModelProviderException failure) {
                    log.warn("reactplan_model_route_failed taskId={} provider={} model={} reason={}",
                            authority.taskId(), route.provider(), route.model(),
                            failure.getClass().getSimpleName());
                } catch (ResponseStatusException failure) {
                    if (failure.getStatusCode().value() == 429) throw failure;
                    log.warn("reactplan_model_route_unavailable taskId={} provider={} model={} status={}",
                            authority.taskId(), route.provider(), route.model(),
                            failure.getStatusCode().value());
                }
            }
            if (response == null || resolvedRoute == null) {
                throw EngineGatewayException.badGateway("MODEL_PROVIDERS_EXHAUSTED");
            }
            ChatResponse.Usage usage = response.usage();
            int prompt = usage == null || usage.promptTokens() == null ? 0 : Math.max(0, usage.promptTokens());
            int completion = usage == null || usage.completionTokens() == null ? 0 : Math.max(0, usage.completionTokens());
            List<ModelToolCall> calls = response.toolCalls() == null ? List.of()
                    : response.toolCalls().stream().map(call -> new ModelToolCall(
                            call.id(), call.function().name(), call.function().arguments())).toList();
            ModelCompletionResult result = new ModelCompletionResult(
                    "1.0", request.clientRequestId(), request.requestDigest(),
                    response.assistantText(), calls, response.finishReason(),
                    new ModelUsage(prompt, completion), false,
                    resolvedRoute.provider(), resolvedRoute.model(),
                    !resolvedRoute.provider().equals(authority.modelProvider())
                            || !resolvedRoute.model().equals(authority.modelName()));
            String serialized = write(result);
            transactions.succeed(authority.taskId(), request.clientRequestId(), serialized,
                    prompt, completion);
            observe(authority, request, "succeeded", null, startedNanos, prompt, completion);
            return result;
        } catch (EngineGatewayException failure) {
            transactions.fail(authority.taskId(), request.clientRequestId(), failure.code());
            observe(authority, request, "failed", failure.code(), startedNanos, 0, 0);
            throw failure;
        } catch (ResponseStatusException failure) {
            transactions.fail(authority.taskId(), request.clientRequestId(),
                    failure.getStatusCode().value() == 429 ? "MODEL_QUOTA_EXHAUSTED" : "MODEL_CONFIGURATION_INVALID");
            observe(authority, request, "failed",
                    failure.getStatusCode().value() == 429 ? "MODEL_QUOTA_EXHAUSTED" : "MODEL_CONFIGURATION_INVALID",
                    startedNanos, 0, 0);
            if (failure.getStatusCode().value() == 429) {
                throw EngineGatewayException.tooManyRequests("MODEL_QUOTA_EXHAUSTED");
            }
            throw EngineGatewayException.badRequest("MODEL_CONFIGURATION_INVALID");
        } catch (RuntimeException failure) {
            transactions.fail(authority.taskId(), request.clientRequestId(), "MODEL_GATEWAY_INTERNAL");
            observe(authority, request, "failed", "MODEL_GATEWAY_INTERNAL", startedNanos, 0, 0);
            throw failure;
        }
    }

    private ChatResponse callModel(
            EngineTaskAuthority authority,
            ModelCompletionRequest request,
            UserSettingsService.ModelEndpoint endpoint) {
        return models.chat(new ChatRequest(
                endpoint.providerKey(), endpoint.modelName(),
                request.messages().stream().map(message -> new ChatMessage(
                        message.role(), message.content(),
                        message.toolCalls() == null ? null : message.toolCalls().stream()
                                .map(call -> new ToolCall(call.id(), "function",
                                        new ToolCall.FunctionCall(call.name(), call.arguments())))
                                .toList(), message.toolCallId())).toList(),
                null, request.maxOutputTokens(),
                request.tools().stream().map(tool -> new ToolSpec(tool.type(),
                        new ToolSpec.FunctionSpec(tool.function().name(),
                                tool.function().description(), tool.function().parameters()))).toList(),
                endpoint.apiKey(), endpoint.apiUrl(), null, null,
                "reactplan:" + authority.taskId() + ":" + request.clientRequestId()));
    }

    private void observe(EngineTaskAuthority authority, ModelCompletionRequest request,
                         String outcome, String errorCode, long startedNanos,
                         int promptTokens, int completionTokens) {
        long durationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                Math.max(0, System.nanoTime() - startedNanos));
        log.info("reactplan_model taskId={} traceId={} phase=model.complete callId={} provider={} model={} outcome={} errorCode={} durationMillis={} promptTokens={} completionTokens={}",
                authority.taskId(), ReactPlanTraceIds.forTask(authority.taskId()), request.clientRequestId(),
                request.provider(), request.model(), outcome, errorCode, durationMillis,
                promptTokens, completionTokens);
    }

    private void validate(EngineTaskAuthority authority, ModelCompletionRequest request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.clientRequestId() == null || !request.clientRequestId().matches("model\\.[a-f0-9]{64}")
                || request.requestDigest() == null || !request.requestDigest().matches("[a-f0-9]{64}")
                || !authority.modelProvider().equals(request.provider())
                || !authority.modelName().equals(request.model())
                || request.messages() == null || request.messages().isEmpty() || request.messages().size() > MAX_MESSAGES
                || request.tools() == null || request.tools().size() > MAX_TOOLS
                || request.maxOutputTokens() < 1 || request.maxOutputTokens() > 4096) {
            throw EngineGatewayException.badRequest("MODEL_REQUEST_INVALID");
        }
        boolean invalidMessage = request.messages().stream().anyMatch(message -> message == null
                || message.role() == null
                || !java.util.Set.of("system", "user", "assistant", "tool").contains(message.role())
                || message.toolCallId() != null && message.toolCallId().length() > 256
                || message.toolCalls() != null && (message.toolCalls().size() > MAX_TOOLS
                        || message.toolCalls().stream().anyMatch(call -> call == null
                                || call.id() == null || call.id().isBlank() || call.id().length() > 256
                                || call.name() == null || call.name().isBlank() || call.name().length() > 128
                                || call.arguments() == null || call.arguments().length() > 65_536)));
        boolean invalidTool = request.tools().stream().anyMatch(tool -> tool == null
                || !"function".equals(tool.type()) || tool.function() == null
                || tool.function().name() == null || tool.function().name().isBlank()
                || tool.function().name().length() > 128 || tool.function().description() == null
                || tool.function().description().length() > 4_000
                || tool.function().parameters() == null || !tool.function().parameters().isObject());
        if (invalidMessage || invalidTool) throw EngineGatewayException.badRequest("MODEL_REQUEST_INVALID");
        int characters = request.messages().stream().mapToInt(message ->
                (message.content() == null ? 0 : message.content().length())
                        + (message.toolCallId() == null ? 0 : message.toolCallId().length())
                        + (message.toolCalls() == null ? 0 : message.toolCalls().stream()
                                .mapToInt(call -> length(call.id()) + length(call.name()) + length(call.arguments())).sum()))
                .sum();
        if (characters > MAX_TOTAL_CHARACTERS) throw EngineGatewayException.tooLarge("MODEL_REQUEST_TOO_LARGE");
    }

    private int length(String value) { return value == null ? 0 : value.length(); }
    private String write(ModelCompletionResult value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
    private String write(ModelCompletionRequest value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
    private ModelCompletionResult replay(String value, EngineTaskAuthority authority) {
        try {
            ModelCompletionResult stored = json.readValue(value, ModelCompletionResult.class);
            String resolvedProvider = stored.resolvedProvider() == null
                    ? authority.modelProvider() : stored.resolvedProvider();
            String resolvedModel = stored.resolvedModel() == null
                    ? authority.modelName() : stored.resolvedModel();
            return new ModelCompletionResult(stored.contractVersion(), stored.clientRequestId(),
                    stored.requestDigest(), stored.content(), stored.toolCalls(), stored.finishReason(),
                    stored.usage(), true, resolvedProvider, resolvedModel,
                    stored.fallbackUsed());
        } catch (JsonProcessingException corrupt) { throw new IllegalStateException("Stored model response is corrupt", corrupt); }
    }
}
