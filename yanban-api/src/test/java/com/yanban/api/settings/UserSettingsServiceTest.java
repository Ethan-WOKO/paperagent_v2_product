package com.yanban.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.OpenRouterProperties;
import com.yanban.core.user.UserAccountPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    @Mock
    SysUserSettingsRepository repository;

    @Mock
    UserModelRepository userModelRepository;

    @Mock
    SettingsCryptoService cryptoService;

    @Mock
    ModelDiscoveryService modelDiscoveryService;

    @Mock
    UserAccountPolicy accountPolicy;

    @Mock
    UserSettingsInitializer initializer;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    OpenRouterProperties openRouterProperties;

    @InjectMocks
    UserSettingsService service;

    @Test
    void getFallsBackToExistingSettingsWhenConcurrentInsertWins() {
        Long userId = 2L;
        SysUserSettings existing = new SysUserSettings(
                userId,
                UserSettingsService.DEFAULT_PROVIDER,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );

        when(repository.findById(userId)).thenReturn(Optional.empty(), Optional.of(existing));
        when(initializer.createDefaultSettings(eq(userId), any(SysUserSettings.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)).thenReturn(List.of());

        UserSettingsResponse response = service.get(userId);

        assertThat(response.defaultProvider()).isEqualTo(UserSettingsService.DEFAULT_PROVIDER);
        assertThat(response.customModels()).isEmpty();
        verify(initializer).createDefaultSettings(eq(userId), any(SysUserSettings.class));
        verify(userModelRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void updateCanSelectOwnedCustomModelAsDefaultProvider() throws Exception {
        Long userId = 4L;
        SysUserSettings settings = new SysUserSettings(
                userId,
                UserSettingsService.DEFAULT_PROVIDER,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );
        UserModel custom = new UserModel(userId, "custom-abc", "Mine", "my-model",
                "https://example.test/v1/chat/completions", "encrypted", false, 101);
        when(repository.findById(userId)).thenReturn(Optional.of(settings));
        when(userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)).thenReturn(List.of(custom));
        when(repository.saveAndFlush(settings)).thenReturn(settings);
        UserSettingsRequest request = new UserSettingsRequest(
                "custom-abc",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        UserSettingsResponse response = service.update(userId, request);

        assertThat(response.defaultProvider()).isEqualTo("custom-abc");
    }

    @Test
    void deleteCustomModelResetsDefaultProviderWhenItWasSelected() {
        Long userId = 5L;
        Long modelId = 55L;
        SysUserSettings settings = new SysUserSettings(
                userId,
                "custom-delete",
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );
        UserModel custom = new UserModel(userId, "custom-delete", "Mine", "my-model",
                "https://example.test/v1/chat/completions", "encrypted", false, 101);
        when(userModelRepository.findById(modelId)).thenReturn(Optional.of(custom));
        when(repository.findById(userId)).thenReturn(Optional.of(settings));
        when(repository.saveAndFlush(settings)).thenReturn(settings);

        service.deleteCustomModel(userId, modelId);

        assertThat(settings.getDefaultProvider()).isEqualTo(UserSettingsService.DEFAULT_PROVIDER);
        verify(userModelRepository).delete(custom);
    }

    @Test
    void deleteCustomModelLeavesDifferentDefaultProviderUntouched() {
        Long userId = 6L;
        Long modelId = 66L;
        SysUserSettings settings = new SysUserSettings(
                userId,
                UserSettingsService.PROVIDER_GLM,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );
        UserModel custom = new UserModel(userId, "custom-other", "Mine", "my-model",
                "https://example.test/v1/chat/completions", "encrypted", false, 101);
        when(userModelRepository.findById(modelId)).thenReturn(Optional.of(custom));
        when(repository.findById(userId)).thenReturn(Optional.of(settings));

        service.deleteCustomModel(userId, modelId);

        assertThat(settings.getDefaultProvider()).isEqualTo(UserSettingsService.PROVIDER_GLM);
        verify(repository, never()).saveAndFlush(settings);
    }

    @Test
    void resolvesOwnedCustomDefaultProviderFromStableFirstModel() {
        Long userId = 7L;
        SysUserSettings settings = settings(
                userId, "custom-default");
        UserModel other = new UserModel(
                userId, "custom-other", "Other", "other-model",
                "https://other.example/v1", "other-key", false, 1);
        UserModel first = new UserModel(
                userId, "custom-default", "First", "first-model",
                "https://first.example/v1", "first-key", false, 2);
        UserModel second = new UserModel(
                userId, "custom-default", "Second", "second-model",
                "https://second.example/v1", "second-key", false, 3);
        when(repository.findById(userId)).thenReturn(
                Optional.of(settings));
        when(userModelRepository
                .findByUserIdOrderBySortOrderAscIdAsc(userId))
                .thenReturn(List.of(other, first, second));
        when(cryptoService.decrypt("first-key"))
                .thenReturn("resolved-key");

        UserSettingsService.ModelEndpoint endpoint =
                service.resolveModelEndpoint(userId, null, null);

        assertThat(endpoint.providerKey()).isEqualTo("custom-default");
        assertThat(endpoint.modelName()).isEqualTo("first-model");
        assertThat(endpoint.apiUrl())
                .isEqualTo("https://first.example/v1");
        assertThat(endpoint.apiKey()).isEqualTo("resolved-key");
    }

    @Test
    void explicitCustomModelPrefersExactProviderAndModel() {
        Long userId = 8L;
        SysUserSettings settings = settings(
                userId, UserSettingsService.DEFAULT_PROVIDER);
        UserModel first = new UserModel(
                userId, "Custom-Provider", "First", "first-model",
                "https://first.example/v1", null, false, 1);
        UserModel exact = new UserModel(
                userId, "Custom-Provider", "Exact", "exact-model",
                "https://exact.example/v1", null, false, 2);
        when(repository.findById(userId)).thenReturn(
                Optional.of(settings));
        when(userModelRepository
                .findByUserIdOrderBySortOrderAscIdAsc(userId))
                .thenReturn(List.of(first, exact));

        UserSettingsService.ModelEndpoint endpoint =
                service.resolveModelEndpoint(
                        userId, "Custom-Provider", "exact-model");

        assertThat(endpoint.providerKey()).isEqualTo("Custom-Provider");
        assertThat(endpoint.modelName()).isEqualTo("exact-model");
        assertThat(endpoint.apiUrl())
                .isEqualTo("https://exact.example/v1");
    }

    private static SysUserSettings settings(
            Long userId, String defaultProvider) {
        return new SysUserSettings(
                userId,
                defaultProvider,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED);
    }
}
