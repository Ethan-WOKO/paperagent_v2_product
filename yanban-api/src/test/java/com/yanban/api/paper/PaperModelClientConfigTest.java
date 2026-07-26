package com.yanban.api.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.LangChain4jChatModelAdapter;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.paper.service.PaperModelExecutionContext;
import com.yanban.paper.service.PaperModelClient;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaperModelClientConfigTest {

    @Test
    void paperModelClientUsesLangChain4jChatRequest() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("polished"))
                .build());
        PaperModelClient client = new PaperModelClientConfig().paperModelClient(chatModel, new PaperModelProperties());

        String result = client.complete("system prompt", "user prompt", 0.2, 1024);

        assertThat(result).isEqualTo("polished");
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        ChatRequest request = captor.getValue();
        assertThat(request.messages()).hasSize(2);
        assertThat(((SystemMessage) request.messages().get(0)).text()).isEqualTo("system prompt");
        assertThat(((UserMessage) request.messages().get(1)).singleText()).isEqualTo("user prompt");
        assertThat(request.temperature()).isEqualTo(0.2);
        assertThat(request.maxOutputTokens()).isEqualTo(1024);
    }

    @Test
    void paperModelClientCanUseConfiguredOpenRouterRuntime() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("hy3"))
                .build());
        PaperModelProperties properties = new PaperModelProperties();
        properties.setProvider("openrouter-hy3-free");
        properties.setModel("tencent/hy3:free");
        properties.setApiUrl("https://openrouter.ai/api/v1/chat/completions");
        properties.setApiKey("or-key");
        PaperModelClient client = new PaperModelClientConfig().paperModelClient(chatModel, properties);

        String result = client.complete("system prompt", "user prompt", 0.2, 1024);

        assertThat(result).isEqualTo("hy3");
        ArgumentCaptor<AgentRuntimeRequest> runtimeCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(chatModel).chat(any(ChatRequest.class), runtimeCaptor.capture());
        AgentRuntimeRequest runtime = runtimeCaptor.getValue();
        assertThat(runtime.provider()).isEqualTo("openrouter-hy3-free");
        assertThat(runtime.model()).isEqualTo("tencent/hy3:free");
        assertThat(runtime.apiUrl()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(runtime.apiKey()).isEqualTo("or-key");
    }

    @Test
    void configuredDeepSeekPaperModelDefaultsToOfficialChatModelWithoutPaperKeyOverride() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("deepseek paper"))
                .build());
        PaperModelProperties properties = new PaperModelProperties();
        properties.setProvider("deepseek");
        PaperModelClient client = new PaperModelClientConfig().paperModelClient(chatModel, properties);

        String result = client.complete("system prompt", "user prompt", 0.2, 1024);

        assertThat(result).isEqualTo("deepseek paper");
        ArgumentCaptor<AgentRuntimeRequest> runtimeCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(chatModel).chat(any(ChatRequest.class), runtimeCaptor.capture());
        AgentRuntimeRequest runtime = runtimeCaptor.getValue();
        assertThat(runtime.provider()).isEqualTo("deepseek");
        assertThat(runtime.model()).isEqualTo("deepseek-v4-flash");
        assertThat(runtime.apiKey()).isNull();
    }

    @Test
    void configuredOpenRouterPaperModelRequiresDedicatedPaperApiKey() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        PaperModelProperties properties = new PaperModelProperties();
        properties.setProvider("openrouter-hy3-free");

        PaperModelClient client = new PaperModelClientConfig().paperModelClient(chatModel, properties);

        assertThatThrownBy(() -> client.complete("system prompt", "user prompt", 0.2, 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YANBAN_PAPER_MODEL_API_KEY");
    }

    @Test
    void paperModelClientResolvesCurrentUserDefaultModelFromExecutionContext() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        when(settingsService.resolveModelEndpoint(42L, null, null)).thenReturn(new UserSettingsService.ModelEndpoint(
                "custom-paper",
                "paper-model",
                "https://paper.example.test/v1/chat/completions",
                "paper-key",
                "custom",
                "Paper Custom"));
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("paper custom"))
                .build());
        PaperModelClient client = new PaperModelClientConfig().paperModelClient(
                chatModel,
                new PaperModelProperties(),
                settingsService);

        String result;
        try (PaperModelExecutionContext.Scope ignored = PaperModelExecutionContext.open(42L)) {
            result = client.complete("system prompt", "user prompt", 0.2, 1024);
        }

        assertThat(result).isEqualTo("paper custom");
        ArgumentCaptor<AgentRuntimeRequest> runtimeCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(chatModel).chat(any(ChatRequest.class), runtimeCaptor.capture());
        AgentRuntimeRequest runtime = runtimeCaptor.getValue();
        assertThat(runtime.provider()).isEqualTo("custom-paper");
        assertThat(runtime.model()).isEqualTo("paper-model");
        assertThat(runtime.apiUrl()).isEqualTo("https://paper.example.test/v1/chat/completions");
        assertThat(runtime.apiKey()).isEqualTo("paper-key");
    }
}
