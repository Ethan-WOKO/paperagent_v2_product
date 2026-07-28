package com.yanban.api.agent.v2.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.provider.DeterministicProductStepTurnAdapter;
import com.yanban.agent.v2.adapter.provider.ProductChatModelProviderAdapter;
import com.yanban.agent.v2.adapter.provider.ProductModelProviderConfiguration;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.kernel.DefaultSingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.StepTurnPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/** Product wiring for one credential-free provider-backed V2 Step turn. */
@Configuration
public class ProductStepTurnConfiguration {
    @Bean
    ModelProvider agentV2ModelProvider(
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            ObjectMapper objectMapper,
            @Value("${yanban.agent.v2.provider:deepseek}") String providerName,
            @Value("${yanban.agent.v2.model:deepseek-chat}") String model) {
        return new ProductChatModelProviderAdapter(
                provider,
                objectMapper,
                new ProductModelProviderConfiguration(providerName, model));
    }

    @Bean("agentV2AllowedTools")
    List<ToolDescriptor> agentV2AllowedTools() {
        return List.of(new ToolDescriptor(
                new ToolId("literature.search"),
                "Search the product literature index using structured criteria.",
                Set.of()));
    }

    @Bean
    StepTurnPort agentV2StepTurnPort(
            ModelProvider provider,
            @Qualifier("agentV2AllowedTools") List<ToolDescriptor> tools,
            @Value("${yanban.agent.v2.max-output-tokens:2048}")
                    int maxOutputTokens,
            @Value("${yanban.agent.v2.temperature:0.2}") double temperature) {
        return new DeterministicProductStepTurnAdapter(
                provider,
                tools,
                new com.yanban.agent.v2.adapter.provider
                        .ProductStepTurnConfiguration(
                                maxOutputTokens, temperature));
    }

    @Bean
    SingleTurnStepKernel singleTurnStepKernel(
            StepTurnPort stepTurnPort,
            EffectIntentRepository effectIntents) {
        return new DefaultSingleTurnStepKernel(stepTurnPort, effectIntents);
    }
}
