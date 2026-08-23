package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ModelCompletionRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ModelCompletionResult;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ModelMessage;
import com.yanban.api.quota.UserQuotaService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ModelProviderException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentEngineModelGatewayTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final UserQuotaService quotas = mock(UserQuotaService.class);
    private final ChatModelProvider models = mock(ChatModelProvider.class);
    private final AgentEngineModelCompletionTransactions transactions =
            mock(AgentEngineModelCompletionTransactions.class);
    private final AgentEngineModelGateway gateway =
            new AgentEngineModelGateway(json, settings, quotas, models, transactions);

    @Test
    void resolvesOwnerEndpointCallsProductModelAndPersistsItsUsageFacts() {
        ModelCompletionRequest request = request("deepseek", "deepseek-v4-flash");
        assertThat(request.requestDigest()).isEqualTo(
                "11f0b506848cfdbea12b5e682806dc0a698032b74515cd501b6e3472cbbbf7a5");
        when(transactions.claim(any(), any(), any(), any(), any(), anyLong())).thenReturn(Optional.empty());
        when(settings.resolveModelEndpoint(7L, "deepseek", "deepseek-v4-flash"))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null, "secret", "builtin", "DeepSeek"));
        when(models.chat(any())).thenReturn(new ChatResponse(
                new ChatMessage("assistant", "done", List.of(), null), "stop",
                new ChatResponse.Usage(11, 4, 15)));

        ModelCompletionResult result = gateway.complete(authority(), request);

        assertThat(result.content()).isEqualTo("done");
        assertThat(result.replayed()).isFalse();
        assertThat(result.resolvedProvider()).isEqualTo("deepseek");
        assertThat(result.resolvedModel()).isEqualTo("deepseek-v4-flash");
        assertThat(result.fallbackUsed()).isFalse();
        ArgumentCaptor<ChatRequest> modelRequest =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(models).chat(modelRequest.capture());
        assertThat(modelRequest.getValue().thinking())
                .isEqualTo(ChatRequest.Thinking.disabled());
        verify(settings).resolveModelEndpoint(7L, "deepseek", "deepseek-v4-flash");
        verify(quotas).assertCanUseAi(7L);
        verify(transactions).succeed(eq(authority().taskId()), eq(request.clientRequestId()),
                any(), eq(11), eq(4));
    }

    @Test
    void fallsBackToNextFrozenConfiguredProviderWhenPreferredProviderFails() {
        ModelCompletionRequest request = requestWithConfiguredIdentity(
                "deepseek", "deepseek-v4-flash");
        when(transactions.claim(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(Optional.empty());
        when(settings.resolveModelEndpoint(7L, "deepseek", "deepseek-v4-flash"))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null, "secret", "builtin", "DeepSeek"));
        when(settings.resolveModelEndpoint(7L, "glm", "glm-4.5-flash"))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        "glm", "glm-4.5-flash", null, "backup", "builtin", "GLM"));
        when(models.chat(any()))
                .thenThrow(new ModelProviderException("primary unavailable"))
                .thenReturn(new ChatResponse(
                        new ChatMessage("assistant", "backup result", List.of(), null), "stop",
                        new ChatResponse.Usage(8, 3, 11)));

        ModelCompletionResult result = gateway.complete(authorityWithFallback(), request);

        assertThat(result.content()).isEqualTo("backup result");
        assertThat(result.resolvedProvider()).isEqualTo("glm");
        assertThat(result.resolvedModel()).isEqualTo("glm-4.5-flash");
        assertThat(result.fallbackUsed()).isTrue();
        ArgumentCaptor<ChatRequest> routed = ArgumentCaptor.forClass(ChatRequest.class);
        verify(models, org.mockito.Mockito.times(2)).chat(routed.capture());
        List<String> fallbackSystemMessages = routed.getAllValues().get(1).messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content).toList();
        assertThat(fallbackSystemMessages)
                .allMatch(message -> message.contains("provider=glm; model=glm-4.5-flash"))
                .noneMatch(message -> message.contains(
                        "provider=deepseek; model=deepseek-v4-flash"));
        verify(quotas).assertCanUseAi(7L);
    }

    @Test
    void exactReplayReturnsStoredResponseWithoutProviderOrQuota() throws Exception {
        ModelCompletionRequest request = request("deepseek", "deepseek-v4-flash");
        ModelCompletionResult stored = new ModelCompletionResult("1.0", request.clientRequestId(),
                request.requestDigest(), "cached", List.of(), "stop",
                new AgentEngineGatewayDtos.ModelUsage(2, 1), false);
        when(transactions.claim(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(Optional.of(json.writeValueAsString(stored)));

        assertThat(gateway.complete(authority(), request).replayed()).isTrue();
        verify(models, never()).chat(any());
        verify(quotas, never()).assertCanUseAi(any());
    }

    @Test
    void rejectsProviderOrModelOutsideSignedAuthority() {
        ModelCompletionRequest request = request("glm", "glm-5.2");
        assertThatThrownBy(() -> gateway.complete(authority(), request))
                .isInstanceOf(EngineGatewayException.class)
                .extracting("code").isEqualTo("MODEL_REQUEST_INVALID");
        verify(transactions, never()).claim(any(), any(), any(), any(), any(), anyLong());
    }

    private ModelCompletionRequest request(String provider, String model) {
        String id = "model." + "c".repeat(64);
        List<ModelMessage> messages = List.of(new ModelMessage("user", "hello", null, null));
        Map<String, Object> semantic = Map.of(
                "contractVersion", "1.0", "clientRequestId", id,
                "provider", provider, "model", model,
                "messages", List.of(Map.of("role", "user", "content", "hello")),
                "tools", List.of(), "maxOutputTokens", 4096);
        return new ModelCompletionRequest("1.0", id,
                ReactPlanCanonicalJson.digest(json, semantic), provider, model,
                messages, List.of(), 4096);
    }

    private ModelCompletionRequest requestWithConfiguredIdentity(
            String provider, String model) {
        String id = "model." + "c".repeat(64);
        String identity = "You are PaperAgent's bounded ReAct executor running with provider="
                + provider + "; model=" + model
                + ". If asked what model you are, report these exact configured values.";
        List<ModelMessage> messages = List.of(
                new ModelMessage("system", identity, null, null),
                new ModelMessage("user", "What model are you?", null, null));
        Map<String, Object> semantic = Map.of(
                "contractVersion", "1.0", "clientRequestId", id,
                "provider", provider, "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", identity),
                        Map.of("role", "user", "content", "What model are you?")),
                "tools", List.of(), "maxOutputTokens", 4096);
        return new ModelCompletionRequest("1.0", id,
                ReactPlanCanonicalJson.digest(json, semantic), provider, model,
                messages, List.of(), 4096);
    }

    private EngineTaskAuthority authority() {
        return new EngineTaskAuthority("task." + "a".repeat(64), "b".repeat(64),
                7, 8, 9, 10, "d".repeat(64), true, true, true,
                "deepseek", "deepseek-v4-flash", Instant.now().plusSeconds(60));
    }

    private EngineTaskAuthority authorityWithFallback() {
        return new EngineTaskAuthority("task." + "a".repeat(64), "b".repeat(64),
                7, 8, 9, 10, "d".repeat(64), true, true, true,
                "deepseek", "deepseek-v4-flash",
                List.of(new EngineModelRouteCandidate("glm", "glm-4.5-flash")),
                Instant.now().plusSeconds(60));
    }
}
