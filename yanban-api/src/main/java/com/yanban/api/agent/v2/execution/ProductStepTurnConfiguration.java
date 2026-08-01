package com.yanban.api.agent.v2.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.provider.DeterministicProductStepTurnAdapter;
import com.yanban.agent.v2.adapter.provider.ProductChatModelProviderAdapter;
import com.yanban.agent.v2.adapter.provider.ProductModelEndpointResolver;
import com.yanban.agent.v2.adapter.provider.ProductStepToolSelector;
import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.kernel.DefaultSingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.StepTurnPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/** Product wiring for one owner-resolved provider-backed V2 Step turn. */
@Configuration
public class ProductStepTurnConfiguration {
    @Bean
    ModelProvider agentV2ModelProvider(
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            ObjectMapper objectMapper,
            ProductModelEndpointResolver endpoints) {
        return new ProductChatModelProviderAdapter(
                provider,
                objectMapper,
                endpoints);
    }

    @Bean
    ProductModelEndpointResolver agentV2ProductModelEndpointResolver(
            JdbcTemplate jdbc, UserSettingsService settings) {
        return new PlanOwnerModelEndpointResolver(jdbc, settings);
    }

    @Bean("agentV2AllowedTools")
    List<ToolDescriptor> agentV2AllowedTools() {
        return V2ProductToolCatalog.descriptors();
    }

    @Bean
    ProductStepToolSelector agentV2StepToolSelector(
            JdbcTemplate jdbc,
            @Qualifier("agentV2AllowedTools") List<ToolDescriptor> tools) {
        Map<String, ToolDescriptor> descriptors = tools.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.id().value(), value -> value));
        return input -> {
            String planId = input.plan().id().value();
            String stepId = input.activeStep().id().value();
            List<String> projectKinds = jdbc.queryForList(
                    "select effect_kind from agent_v2_project_analysis_steps "
                            + "where plan_id = ? and step_id = ?",
                    String.class, planId, stepId);
            List<String> candidateKinds = jdbc.queryForList(
                    "select effect_kind from agent_v2_project_candidate_steps "
                            + "where plan_id = ? and step_id = ?",
                    String.class, planId, stepId);
            List<String> literaturePlans = jdbc.queryForList(
                    "select plan_id from agent_v2_literature_deliveries "
                            + "where plan_id = ?",
                    String.class, planId);
            String kind = null;
            if (projectKinds.size() == 1 && candidateKinds.isEmpty()
                    && literaturePlans.isEmpty()) {
                kind = projectKinds.get(0);
            } else if (candidateKinds.size() == 1 && projectKinds.isEmpty()
                    && literaturePlans.isEmpty()) {
                kind = candidateKinds.get(0);
            } else if (projectKinds.isEmpty()
                    && literaturePlans.size() == 1
                    && "literature-search".equals(stepId)) {
                kind = "literature.search";
            }
            ToolDescriptor selected = descriptors.get(kind);
            return selected == null ? List.of() : List.of(selected);
        };
    }

    @Bean
    StepTurnPort agentV2StepTurnPort(
            ModelProvider provider,
            ProductStepToolSelector toolSelector,
            @Value("${yanban.agent.v2.max-output-tokens:2048}")
                    int maxOutputTokens,
            @Value("${yanban.agent.v2.temperature:0.2}") double temperature) {
        return new DeterministicProductStepTurnAdapter(
                provider,
                toolSelector,
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
