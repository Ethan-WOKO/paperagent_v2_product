package com.yanban.api.agent;

import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.DeepSeekProperties;
import com.yanban.core.model.GlmProperties;
import org.springframework.stereotype.Component;

/** Resolves the server-owned output-token budget before runtime coordination. */
@Component
public class AgentRuntimeTokenBudgetResolver {

    static final int DEFAULT_MAX_TOKENS = 4096;

    private final DeepSeekProperties deepSeekProperties;
    private final GlmProperties glmProperties;

    public AgentRuntimeTokenBudgetResolver(DeepSeekProperties deepSeekProperties,
                                           GlmProperties glmProperties) {
        this.deepSeekProperties = deepSeekProperties;
        this.glmProperties = glmProperties;
    }

    public int resolve(String providerKey) {
        Integer configured = null;
        if (UserSettingsService.DEFAULT_PROVIDER.equalsIgnoreCase(providerKey)) {
            configured = deepSeekProperties.getMaxTokens();
        } else if (UserSettingsService.PROVIDER_GLM.equalsIgnoreCase(providerKey)) {
            configured = glmProperties.getMaxTokens();
        }
        return configured != null && configured > 0 ? configured : DEFAULT_MAX_TOKENS;
    }
}
