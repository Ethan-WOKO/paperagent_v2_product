package com.yanban.api.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxOutputAnalysisServiceTest {
    @Test
    void analysisUsesNoToolsAndCannotChangeExecutionFacts() {
        ChatModelProvider models = mock(ChatModelProvider.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.resolveModelEndpoint(7L, "deepseek", "model"))
                .thenReturn(new UserSettingsService.ModelEndpoint("deepseek", "model", "https://model.invalid", "ephemeral", "builtin", "test"));
        when(models.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("The program printed six rows."), "stop", null));
        SandboxOutputAnalysisService service = new SandboxOutputAnalysisService(models, settings);
        AgentSession session = new AgentSession(7L, "test", "deepseek", "model", 8, true,
                AgentSessionScope.PROJECT, 9L);
        SandboxReceipt receipt = receipt();

        String summary = service.analyze(7L, session, receipt, "trace");

        assertThat(summary).isEqualTo("The program printed six rows.");
        assertThat(receipt.status()).isEqualTo(SandboxExecutionStatus.SUCCEEDED);
        assertThat(receipt.exitCode()).isZero();
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(models).chat(request.capture());
        assertThat(request.getValue().tools()).isEmpty();
        assertThat(request.getValue().messages()).allMatch(message -> message.toolCalls() == null);
    }

    private SandboxReceipt receipt() {
        Instant now = Instant.now();
        return new SandboxReceipt("exec", "key", "a".repeat(64), 7, 9, 11, 12, 13, 1,
                "b".repeat(64), "c".repeat(64), "e2b", SandboxExecutionStatus.SUCCEEDED,
                0, "[1, 2, 3]", "", false, Map.of(), now, now, null);
    }
}
