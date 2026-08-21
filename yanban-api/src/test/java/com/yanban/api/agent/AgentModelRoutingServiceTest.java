package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ModelProviderException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class AgentModelRoutingServiceTest {

    @Test
    void usesFallbackProviderWithItsOwnCredentialAndActualIdentity() {
        ChatModelProvider models = mock(ChatModelProvider.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.configuredModelReferences(7L)).thenReturn(List.of(
                new UserSettingsService.ModelReference("deepseek", "deepseek-chat"),
                new UserSettingsService.ModelReference("glm", "glm-4-flash")));
        when(settings.resolveModelEndpoint(7L, "glm", "glm-4-flash"))
                .thenReturn(endpoint("glm", "glm-4-flash", "glm-secret"));
        when(models.chat(any())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            if ("deepseek".equals(request.provider())) {
                throw new ModelProviderException("DeepSeek API error: HTTP 401");
            }
            return new ChatResponse(ChatMessage.assistant("ok"), "stop", null);
        });

        AgentModelRoutingService.RoutedChatResponse result =
                new AgentModelRoutingService(models, settings).chat(7L, primary());

        assertThat(result.resolvedProvider()).isEqualTo("glm");
        assertThat(result.resolvedModel()).isEqualTo("glm-4-flash");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.response().assistantText()).isEqualTo("ok");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(models, org.mockito.Mockito.times(2)).chat(requests.capture());
        ChatRequest fallback = requests.getAllValues().get(1);
        assertThat(fallback.apiKey()).isEqualTo("glm-secret");
        assertThat(fallback.apiKey()).isNotEqualTo("bad-deepseek-key");
        assertThat(fallback.messages().get(0).content())
                .contains("provider=glm; model=glm-4-flash")
                .contains("actual runtime identity");
    }

    @Test
    void streamingFallsBackOnlyBeforeAnyPrimaryChunkWasEmitted() {
        ChatModelProvider models = mock(ChatModelProvider.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.configuredModelReferences(7L)).thenReturn(List.of(
                new UserSettingsService.ModelReference("deepseek", "deepseek-chat"),
                new UserSettingsService.ModelReference("glm", "glm-4-flash")));
        when(settings.resolveModelEndpoint(7L, "glm", "glm-4-flash"))
                .thenReturn(endpoint("glm", "glm-4-flash", "glm-secret"));
        when(models.streamChat(any())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            return "deepseek".equals(request.provider())
                    ? Flux.error(new ModelProviderException("HTTP 401"))
                    : Flux.just(ChatChunk.token("glm-answer"), ChatChunk.done("stop"));
        });

        List<ChatChunk> chunks = new AgentModelRoutingService(models, settings)
                .stream(7L, primary()).collectList().block();

        assertThat(chunks).isNotNull();
        assertThat(chunks).extracting(ChatChunk::content).contains("glm-answer");
    }

    @Test
    void exposesLastFailureWhenEveryConfiguredRouteFails() {
        ChatModelProvider models = mock(ChatModelProvider.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.configuredModelReferences(7L)).thenReturn(List.of(
                new UserSettingsService.ModelReference("deepseek", "deepseek-chat"),
                new UserSettingsService.ModelReference("glm", "glm-4-flash")));
        when(settings.resolveModelEndpoint(7L, "glm", "glm-4-flash"))
                .thenReturn(endpoint("glm", "glm-4-flash", "glm-secret"));
        when(models.chat(any()))
                .thenThrow(new ModelProviderException("deepseek failed"))
                .thenThrow(new ModelProviderException("glm failed"));

        assertThatThrownBy(() -> new AgentModelRoutingService(models, settings).chat(7L, primary()))
                .isInstanceOf(ModelProviderException.class)
                .hasMessage("glm failed");
    }

    @Test
    void frozenRoutesStillReportFallbackWhenPrimaryConfigurationCannotResolve() {
        ChatModelProvider models = mock(ChatModelProvider.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.resolveModelEndpoint(7L, "missing-custom", "model-a"))
                .thenThrow(new IllegalStateException("not configured"));
        when(settings.resolveModelEndpoint(7L, "glm", "glm-4-flash"))
                .thenReturn(endpoint("glm", "glm-4-flash", "glm-secret"));
        when(models.chat(any())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            if ("missing-custom".equals(request.provider())) {
                throw new ModelProviderException("unsupported");
            }
            return new ChatResponse(ChatMessage.assistant("ok"), "stop", null);
        });

        AgentModelRoutingService.RoutedChatResponse result =
                new AgentModelRoutingService(models, settings).chatConfigured(
                        7L, primary(), List.of(
                                new UserSettingsService.ModelReference("missing-custom", "model-a"),
                                new UserSettingsService.ModelReference("glm", "glm-4-flash")));

        assertThat(result.resolvedProvider()).isEqualTo("glm");
        assertThat(result.fallbackUsed()).isTrue();
    }

    private ChatRequest primary() {
        return new ChatRequest(
                "deepseek", "deepseek-chat", List.of(ChatMessage.user("hello")),
                0.1, 128, List.of(), "bad-deepseek-key", null,
                null, null, "route-test");
    }

    private UserSettingsService.ModelEndpoint endpoint(String provider, String model, String key) {
        return new UserSettingsService.ModelEndpoint(
                provider, model, null, key, "builtin", provider);
    }
}
