package com.yanban.api.agent.v2.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.provider.ProductModelEndpoint;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import io.paperagent.v2.persistence.EffectIntentRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

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
        var endpoints = (com.yanban.agent.v2.adapter.provider
                .ProductModelEndpointResolver) planId ->
                new ProductModelEndpoint(
                        "default-provider", "default-model",
                        "transient-key", null);
        var provider = configuration.agentV2ModelProvider(
                fake, new ObjectMapper(), endpoints);
        var tools = configuration.agentV2AllowedTools();
        var turn = configuration.agentV2StepTurnPort(
                provider, input -> List.of(tools.get(0)), 512, 0.1d);
        var kernel = configuration.singleTurnStepKernel(
                turn, mock(EffectIntentRepository.class));

        assertNotNull(provider);
        assertNotNull(turn);
        assertNotNull(kernel);
        assertEquals(
                com.yanban.api.agent.v2.tool.V2ProductToolCatalog
                        .descriptors(),
                tools);
        var bibtex = tools.stream()
                .filter(tool -> "project.bibtex.audit".equals(
                        tool.id().value()))
                .findFirst().orElseThrow();
        assertEquals(
                java.util.Set.of(io.paperagent.v2.contracts.Capability.READ_PROJECT),
                bibtex.requiredCapabilities());
    }

    @Test
    void selectorReturnsOnlyPersistedCandidateComposeAuthority() {
        var configuration = new ProductStepTurnConfiguration();
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(contains("agent_v2_project_analysis_steps"),
                eq(String.class), any(), any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("agent_v2_project_candidate_steps"),
                eq(String.class), any(), any()))
                .thenReturn(List.of("project.candidate.compose"));
        when(jdbc.queryForList(contains("agent_v2_literature_deliveries"),
                eq(String.class), any())).thenReturn(List.of());
        var input = mock(io.paperagent.v2.runtime.execution.kernel.StepTurnInput.class);
        var plan = mock(io.paperagent.v2.contracts.Plan.class);
        var step = mock(io.paperagent.v2.contracts.PlanStep.class);
        when(input.plan()).thenReturn(plan);
        when(input.activeStep()).thenReturn(step);
        when(plan.id()).thenReturn(new io.paperagent.v2.contracts.PlanId("plan"));
        when(step.id()).thenReturn(new io.paperagent.v2.contracts.PlanStepId(
                "project-candidate-compose"));

        var selected = configuration.agentV2StepToolSelector(
                jdbc, configuration.agentV2AllowedTools()).select(input);

        assertEquals(1, selected.size());
        assertEquals("project.candidate.compose", selected.get(0).id().value());
    }
}
