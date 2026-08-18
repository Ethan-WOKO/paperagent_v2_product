package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.yanban.core.model.ChatResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
    void resolvesOwnerEndpointCallsProductModelAndRecordsUsageOnce() {
        ModelCompletionRequest request = request("deepseek", "deepseek-v4-flash");
        assertThat(request.requestDigest()).isEqualTo(
                "11f0b506848cfdbea12b5e682806dc0a698032b74515cd501b6e3472cbbbf7a5");
        when(transactions.claim(any(), any(), any())).thenReturn(Optional.empty());
        when(settings.resolveModelEndpoint(7L, "deepseek", "deepseek-v4-flash"))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null, "secret", "builtin", "DeepSeek"));
        when(models.chat(any())).thenReturn(new ChatResponse(
                new ChatMessage("assistant", "done", List.of(), null), "stop",
                new ChatResponse.Usage(11, 4, 15)));

        ModelCompletionResult result = gateway.complete(authority(), request);

        assertThat(result.content()).isEqualTo("done");
        assertThat(result.replayed()).isFalse();
        verify(settings).resolveModelEndpoint(7L, "deepseek", "deepseek-v4-flash");
        verify(quotas).assertCanUseAi(7L);
        verify(transactions).succeed(eq(authority().taskId()), eq(request.clientRequestId()),
                any(), eq(7L), eq(11), eq(4));
    }

    @Test
    void exactReplayReturnsStoredResponseWithoutProviderOrQuota() throws Exception {
        ModelCompletionRequest request = request("deepseek", "deepseek-v4-flash");
        ModelCompletionResult stored = new ModelCompletionResult("1.0", request.clientRequestId(),
                request.requestDigest(), "cached", List.of(), "stop",
                new AgentEngineGatewayDtos.ModelUsage(2, 1), false);
        when(transactions.claim(any(), any(), any()))
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
        verify(transactions, never()).claim(any(), any(), any());
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

    private EngineTaskAuthority authority() {
        return new EngineTaskAuthority("task." + "a".repeat(64), "b".repeat(64),
                7, 8, 9, 10, "d".repeat(64), true, true, true,
                "deepseek", "deepseek-v4-flash", Instant.now().plusSeconds(60));
    }
}
