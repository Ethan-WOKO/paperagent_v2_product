package com.yanban.api.settings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UserSettingsResponse(
        String defaultProvider,
        boolean deepseekApiKeyConfigured,
        boolean glmApiKeyConfigured,
        boolean githubPatConfigured,
        String deepseekModel,
        String glmModel,
        List<String> deepseekModels,
        List<String> glmModels,
        BigDecimal deepseekTemperature,
        Integer maxSteps,
        Boolean ragDefaultEnabled,
        List<String> filesystemRoots,
        List<String> disabledSkills,
        List<UserModelResponse> customModels,
        Instant updatedAt
) {
    public static UserSettingsResponse from(SysUserSettings settings,
                                           List<String> filesystemRoots,
                                           List<String> disabledSkills,
                                           List<String> deepseekModels,
                                           List<String> glmModels,
                                           List<UserModelResponse> customModels,
                                           boolean githubPatConfigured) {
        return new UserSettingsResponse(
                settings.getDefaultProvider(),
                settings.getDeepseekApiKeyEncrypted() != null && !settings.getDeepseekApiKeyEncrypted().isBlank(),
                settings.getGlmApiKeyEncrypted() != null && !settings.getGlmApiKeyEncrypted().isBlank(),
                githubPatConfigured,
                settings.getDeepseekModel(),
                settings.getGlmModel(),
                deepseekModels,
                glmModels,
                settings.getDeepseekTemperature(),
                settings.getMaxSteps(),
                settings.getRagDefaultEnabled(),
                filesystemRoots,
                disabledSkills,
                customModels,
                settings.getUpdatedAt()
        );
    }
}
