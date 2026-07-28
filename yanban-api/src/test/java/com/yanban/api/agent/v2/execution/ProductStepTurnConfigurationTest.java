package com.yanban.api.agent.v2.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import io.paperagent.v2.persistence.EffectIntentRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ProductStepTurnConfigurationTest {
    @Test
    void wiresCredentialFreeProviderTurnAndKernelWithFakesOnly() {
        ProductStepTurnConfiguration configuration =
                new ProductStepTurnConfiguration();
        ChatModelProvider fake = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "fake";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                return new ChatResponse(
                        com.yanban.core.model.ChatMessage.assistant("done"),
                        "stop",
                        null);
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("no streaming");
            }
        };
        var provider = configuration.agentV2ModelProvider(
                fake, new ObjectMapper(), "deepseek", "test-model");
        var tools = configuration.agentV2AllowedTools();
        var turn = configuration.agentV2StepTurnPort(
                provider, tools, 512, 0.1d);
        var kernel = configuration.singleTurnStepKernel(
                turn, mock(EffectIntentRepository.class));

        assertNotNull(provider);
        assertNotNull(turn);
        assertNotNull(kernel);
        assertEquals("literature.search", tools.get(0).id().value());
    }
}
