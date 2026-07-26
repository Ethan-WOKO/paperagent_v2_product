package com.yanban.api.settings;

import java.util.List;
import com.yanban.core.model.OpenRouterProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class UserSettingsInitializer {

    private final SysUserSettingsRepository settingsRepository;
    private final UserModelRepository userModelRepository;
    private final OpenRouterProperties openRouterProperties;

    UserSettingsInitializer(SysUserSettingsRepository settingsRepository,
                            UserModelRepository userModelRepository,
                            OpenRouterProperties openRouterProperties) {
        this.settingsRepository = settingsRepository;
        this.userModelRepository = userModelRepository;
        this.openRouterProperties = openRouterProperties;
    }

    // A duplicate settings row can be produced when the client loads several
    // authenticated resources immediately after login. Keep the insert in its
    // own transaction so the caller can safely re-read the row when another
    // request won that race.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SysUserSettings createDefaultSettings(Long userId, SysUserSettings defaults) {
        SysUserSettings created = settingsRepository.saveAndFlush(defaults);
        seedBuiltinCustomModels(userId);
        return created;
    }

    private void seedBuiltinCustomModels(Long userId) {
        if (!userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).isEmpty()) {
            return;
        }
        List<UserModel> builtins = List.of(
                new UserModel(userId, "deepseek", "DeepSeek", UserSettingsService.DEFAULT_DEEPSEEK_MODEL, null, null, true, 1),
                new UserModel(userId, "deepseek", "DeepSeek", "deepseek-v4-pro", null, null, true, 2),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-5.2", null, null, true, 5),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-5.1", null, null, true, 6),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-5", null, null, true, 7),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-4.7", null, null, true, 8),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-4.6", null, null, true, 9),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-4.5-air", null, null, true, 10),
                new UserModel(userId, "openrouter-hy3-free", "OpenRouter", openRouterProperties.getHy3FreeModel(), openRouterProperties.getApiUrl(), null, true, 20),
                new UserModel(userId, "openrouter-hy3", "OpenRouter", openRouterProperties.getHy3Model(), openRouterProperties.getApiUrl(), null, true, 21)
        );
        userModelRepository.saveAllAndFlush(builtins);
    }
}
