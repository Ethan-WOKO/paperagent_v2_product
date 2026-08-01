package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.core.model.DeepSeekProperties;
import com.yanban.core.model.GlmProperties;
import org.junit.jupiter.api.Test;

class AgentRuntimeTokenBudgetResolverTest {

    private final DeepSeekProperties deepSeek = new DeepSeekProperties();
    private final GlmProperties glm = new GlmProperties();
    private final AgentRuntimeTokenBudgetResolver resolver =
            new AgentRuntimeTokenBudgetResolver(deepSeek, glm);

    @Test
    void resolvesConfiguredBuiltInProviderBudgets() {
        deepSeek.setMaxTokens(6144);
        glm.setMaxTokens(3072);

        assertThat(resolver.resolve("deepseek")).isEqualTo(6144);
        assertThat(resolver.resolve("glm")).isEqualTo(3072);
    }

    @Test
    void usesBoundedServerDefaultForCustomOrInvalidConfiguration() {
        deepSeek.setMaxTokens(null);

        assertThat(resolver.resolve("deepseek"))
                .isEqualTo(AgentRuntimeTokenBudgetResolver.DEFAULT_MAX_TOKENS);
        assertThat(resolver.resolve("custom-provider"))
                .isEqualTo(AgentRuntimeTokenBudgetResolver.DEFAULT_MAX_TOKENS);
    }
}
