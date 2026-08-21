package com.yanban.api.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.OpenRouterProperties;
import com.yanban.core.user.UserAccountPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserSettingsService {

    public static final String DEFAULT_PROVIDER = "deepseek";
    public static final String PROVIDER_GLM = "glm";
    public static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash";
    public static final String DEFAULT_GLM_MODEL = "glm-5.2";
    public static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");
    public static final int DEFAULT_MAX_STEPS = 20;
    public static final boolean DEFAULT_RAG_ENABLED = true;
    public static final List<String> DEFAULT_FILESYSTEM_ROOTS = List.of("workspace");
    public static final List<String> DEFAULT_DEEPSEEK_MODELS = List.of(
            "deepseek-v4-flash",
            "deepseek-v4-pro"
    );
    public static final List<String> DEFAULT_GLM_MODELS = List.of(
            "glm-5.2",
            "glm-5.1",
            "glm-5",
            "glm-5-turbo",
            "glm-4.7",
            "glm-4.7-flashx",
            "glm-4.6",
            "glm-4.5-air",
            "glm-4.5-airx",
            "glm-4-long",
            "glm-4.7-flash",
            "glm-4.5-flash",
            "glm-4-flash-250414",
            "glm-4-flash"
    );

    private final SysUserSettingsRepository repository;
    private final UserModelRepository userModelRepository;
    private final SettingsCryptoService cryptoService;
    private final ModelDiscoveryService modelDiscoveryService;
    private final ObjectMapper objectMapper;
    private final UserAccountPolicy accountPolicy;
    private final UserSettingsInitializer initializer;
    private final OpenRouterProperties openRouterProperties;

    public UserSettingsService(SysUserSettingsRepository repository,
                               UserModelRepository userModelRepository,
                               SettingsCryptoService cryptoService,
                               ModelDiscoveryService modelDiscoveryService,
                               ObjectMapper objectMapper,
                               UserAccountPolicy accountPolicy,
                               UserSettingsInitializer initializer,
                               OpenRouterProperties openRouterProperties) {
        this.repository = repository;
        this.userModelRepository = userModelRepository;
        this.cryptoService = cryptoService;
        this.modelDiscoveryService = modelDiscoveryService;
        this.objectMapper = objectMapper;
        this.accountPolicy = accountPolicy;
        this.initializer = initializer;
        this.openRouterProperties = openRouterProperties;
    }

    @Transactional
    public UserSettingsResponse get(Long userId) {
        SysUserSettings settings = getOrCreate(userId);
        return toResponse(settings);
    }

    @Transactional
    public UserSettingsResponse update(Long userId, UserSettingsRequest request) {
        accountPolicy.assertSettingsMutable(userId);
        SysUserSettings settings = getOrCreate(userId);
        String provider = resolveDefaultProviderForUpdate(userId, request.defaultProvider(), settings.getDefaultProvider());
        String deepseekModel = resolveDeepseekModel(request.deepseekModel(), settings.getDeepseekModel());
        String glmModel = StringUtils.hasText(request.glmModel()) ? request.glmModel().trim() : settings.getGlmModel();
        BigDecimal temperature = request.deepseekTemperature() != null ? request.deepseekTemperature() : settings.getDeepseekTemperature();
        Integer maxSteps = request.maxSteps() != null ? request.maxSteps() : settings.getMaxSteps();
        Boolean ragDefaultEnabled = request.ragDefaultEnabled() != null ? request.ragDefaultEnabled() : settings.getRagDefaultEnabled();
        String encryptedDeepseekApiKey = resolveEncryptedApiKey(settings.getDeepseekApiKeyEncrypted(), request.deepseekApiKey());
        String encryptedGlmApiKey = resolveEncryptedApiKey(settings.getGlmApiKeyEncrypted(), request.glmApiKey());
        String encryptedGithubPat = resolveEncryptedApiKey(settings.getGithubPatEncrypted(), request.githubPat());
        String filesystemRootsText = request.filesystemRoots() == null ? settings.getFilesystemRootsText() : writeJson(request.filesystemRoots());
        String disabledSkillsJson = request.disabledSkills() == null ? settings.getDisabledSkillsJson() : writeJson(request.disabledSkills());
        List<String> deepseekModels = request.deepseekModels() == null
                ? parseDeepseekModels(settings)
                : normalizeDeepseekModels(request.deepseekModels());
        String deepseekModelsText = writeJson(deepseekModels);
        String glmModelsText = request.glmModels() == null ? settings.getGlmModelsText() : writeJson(request.glmModels());
        settings.update(provider, encryptedDeepseekApiKey, encryptedGlmApiKey, deepseekModel, glmModel,
                encryptedGithubPat, filesystemRootsText, disabledSkillsJson, temperature, maxSteps, ragDefaultEnabled,
                deepseekModelsText, glmModelsText);
        return toResponse(repository.saveAndFlush(settings));
    }

    @Transactional
    public UserSettingsResponse refreshProviderModels(Long userId, String provider) {
        accountPolicy.assertSettingsMutable(userId);
        SysUserSettings settings = getOrCreate(userId);
        String resolvedProvider = normalizeProvider(provider, settings.getDefaultProvider());
        if (DEFAULT_PROVIDER.equals(resolvedProvider)) {
            List<String> models = normalizeDeepseekModels(
                    modelDiscoveryService.discoverDeepSeekModels(decryptDeepseekApiKey(settings)));
            String selectedModel = models.contains(settings.getDeepseekModel())
                    ? settings.getDeepseekModel()
                    : models.get(0);
            settings.update(settings.getDefaultProvider(),
                    settings.getDeepseekApiKeyEncrypted(),
                    settings.getGlmApiKeyEncrypted(),
                    selectedModel,
                    settings.getGlmModel(),
                    settings.getGithubPatEncrypted(),
                    settings.getFilesystemRootsText(),
                    settings.getDisabledSkillsJson(),
                    settings.getDeepseekTemperature(),
                    settings.getMaxSteps(),
                    settings.getRagDefaultEnabled(),
                    writeJson(models),
                    settings.getGlmModelsText());
            return toResponse(repository.saveAndFlush(settings));
        }
        if (PROVIDER_GLM.equals(resolvedProvider)) {
            String selectedModel = DEFAULT_GLM_MODELS.contains(settings.getGlmModel())
                    ? settings.getGlmModel()
                    : DEFAULT_GLM_MODELS.get(0);
            settings.update(settings.getDefaultProvider(),
                    settings.getDeepseekApiKeyEncrypted(),
                    settings.getGlmApiKeyEncrypted(),
                    settings.getDeepseekModel(),
                    selectedModel,
                    settings.getGithubPatEncrypted(),
                    settings.getFilesystemRootsText(),
                    settings.getDisabledSkillsJson(),
                    settings.getDeepseekTemperature(),
                    settings.getMaxSteps(),
                    settings.getRagDefaultEnabled(),
                    settings.getDeepseekModelsText(),
                    writeJson(DEFAULT_GLM_MODELS));
            return toResponse(repository.saveAndFlush(settings));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
    }

    @Transactional
    public SysUserSettings getOrCreate(Long userId) {
        return repository.findById(userId)
                .orElseGet(() -> {
                    SysUserSettings defaults = defaultSettings(userId);
                    try {
                        return initializer.createDefaultSettings(userId, defaults);
                    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                        // A concurrent request won the insert race in a separate transaction.
                        return repository.findById(userId)
                                .orElseThrow(() -> ex);
                    }
                });
    }

    // ===== Custom model CRUD =====

    @Transactional
    public List<UserModelResponse> listCustomModels(Long userId) {
        ensureUserInitialized(userId);
        return userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(this::toUserModelResponse)
                .toList();
    }

    @Transactional
    public UserModelResponse createCustomModel(Long userId, UserModelRequest request) {
        accountPolicy.assertSettingsMutable(userId);
        ensureUserInitialized(userId);
        List<UserModel> existing = userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        int nextSortOrder = existing.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getBuiltin()))
                .mapToInt(UserModel::getSortOrder)
                .max()
                .orElse(100) + 1;
        String providerKey = "custom-" + System.currentTimeMillis();
        UserModel model = new UserModel(
                userId,
                providerKey,
                request.label().trim(),
                request.modelName().trim(),
                request.apiUrl().trim(),
                StringUtils.hasText(request.apiKey()) ? cryptoService.encrypt(request.apiKey().trim()) : null,
                false,
                nextSortOrder
        );
        return toUserModelResponse(userModelRepository.saveAndFlush(model));
    }

    @Transactional
    public UserModelResponse updateCustomModel(Long userId, Long modelId, UserModelRequest request) {
        accountPolicy.assertSettingsMutable(userId);
        UserModel model = findOwnedCustomModel(userId, modelId);
        String encryptedApiKey = resolveEncryptedApiKey(model.getApiKeyEncrypted(), request.apiKey());
        model.update(request.label().trim(), request.modelName().trim(), request.apiUrl().trim(), encryptedApiKey);
        return toUserModelResponse(userModelRepository.saveAndFlush(model));
    }

    @Transactional
    public void deleteCustomModel(Long userId, Long modelId) {
        accountPolicy.assertSettingsMutable(userId);
        UserModel model = findOwnedCustomModel(userId, modelId);
        resetDefaultProviderIfNeeded(userId, model.getProviderKey());
        userModelRepository.delete(model);
        userModelRepository.flush();
    }

    private UserModel findOwnedCustomModel(Long userId, Long modelId) {
        UserModel model = userModelRepository.findById(modelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "自定义模型不存在"));
        if (!model.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "自定义模型不存在");
        }
        if (Boolean.TRUE.equals(model.getBuiltin())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置模型不可修改或删除");
        }
        return model;
    }

    private void ensureUserInitialized(Long userId) {
        getOrCreate(userId);
    }

    // ===== Model resolution for AgentService =====

    /**
     * Returns the model config (providerKey, modelName, apiUrl, apiKey) for a given provider snapshot.
     * For built-in providers (deepseek/glm), uses settings + global config.
     * For custom providers, looks up the user_models table.
     */
    public ModelEndpoint resolveModelEndpoint(Long userId, String provider, String model) {
        SysUserSettings settings = getOrCreate(userId);
        String resolvedProvider = (provider == null || provider.isBlank())
                ? settings.getDefaultProvider() : provider.trim();
        String builtinProvider = resolvedProvider.toLowerCase(
                java.util.Locale.ROOT);

        if (DEFAULT_PROVIDER.equals(builtinProvider)) {
            return new ModelEndpoint(builtinProvider,
                    resolveDeepseekModel(model, settings.getDeepseekModel()),
                    null,
                    decryptDeepseekApiKey(settings),
                    "builtin",
                    "DeepSeek");
        }
        if (PROVIDER_GLM.equals(builtinProvider)) {
            return new ModelEndpoint(builtinProvider,
                    StringUtils.hasText(model) ? model : settings.getGlmModel(),
                    null,
                    decryptGlmApiKey(settings),
                    "builtin",
                    "GLM");
        }
        // Custom provider: providerKey stored as-is (case-sensitive match by providerKey)
        List<UserModel> userModels = userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        List<UserModel> providerMatches = userModels.stream()
                .filter(value -> value.getProviderKey().equals(
                        resolvedProvider))
                .toList();
        String requestedModel = StringUtils.hasText(model)
                ? model.trim() : null;
        Optional<UserModel> match = requestedModel == null
                ? Optional.empty()
                : providerMatches.stream()
                        .filter(value -> value.getModelName().equals(
                                requestedModel))
                        .findFirst();
        if (match.isEmpty() && !providerMatches.isEmpty()) {
            match = providerMatches.stream().findFirst();
        }
        if (match.isEmpty() && requestedModel != null) {
            // Explicit-model fallback remains owner-qualified.
            match = userModels.stream()
                    .filter(value -> value.getModelName().equals(
                            requestedModel))
                    .findFirst();
        }
        if (match.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Model provider is not configured: "
                            + resolvedProvider);
        }
        UserModel um = match.get();
        return new ModelEndpoint(um.getProviderKey(),
                um.getModelName(),
                resolveCustomModelApiUrl(um),
                resolveCustomModelApiKey(um),
                Boolean.TRUE.equals(um.getBuiltin()) ? "builtin-custom" : "custom",
                um.getProviderLabel());
    }

    public List<ModelReference> configuredModelReferences(Long userId) {
        SysUserSettings settings = getOrCreate(userId);
        List<ModelReference> references = new java.util.ArrayList<>();
        references.add(new ModelReference(DEFAULT_PROVIDER,
                resolveDeepseekModel(null, settings.getDeepseekModel())));
        references.add(new ModelReference(PROVIDER_GLM, settings.getGlmModel()));
        userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(value -> new ModelReference(value.getProviderKey(), value.getModelName()))
                .forEach(references::add);
        return references.stream().distinct().limit(8).toList();
    }

    public String decryptDeepseekApiKey(SysUserSettings settings) {
        if (!StringUtils.hasText(settings.getDeepseekApiKeyEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(settings.getDeepseekApiKeyEncrypted());
    }

    public String decryptGlmApiKey(SysUserSettings settings) {
        if (!StringUtils.hasText(settings.getGlmApiKeyEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(settings.getGlmApiKeyEncrypted());
    }

    public String decryptGithubPat(SysUserSettings settings) {
        if (!StringUtils.hasText(settings.getGithubPatEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(settings.getGithubPatEncrypted());
    }

    public String decryptCustomModelApiKey(UserModel model) {
        if (!StringUtils.hasText(model.getApiKeyEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(model.getApiKeyEncrypted());
    }

    private String resolveCustomModelApiUrl(UserModel model) {
        if (isOpenRouterModel(model) && !StringUtils.hasText(model.getApiUrl())) {
            return openRouterProperties.getApiUrl();
        }
        return model.getApiUrl();
    }

    private String resolveCustomModelApiKey(UserModel model) {
        String modelKey = decryptCustomModelApiKey(model);
        if (StringUtils.hasText(modelKey)) {
            return modelKey;
        }
        return isOpenRouterModel(model) ? openRouterProperties.getApiKey() : null;
    }

    public List<String> parseFilesystemRoots(SysUserSettings settings) {
        return readStringList(settings.getFilesystemRootsText(), DEFAULT_FILESYSTEM_ROOTS);
    }

    public List<String> parseDisabledSkills(SysUserSettings settings) {
        return readStringList(settings.getDisabledSkillsJson(), List.of());
    }

    public List<String> parseDeepseekModels(SysUserSettings settings) {
        return normalizeDeepseekModels(readStringList(settings.getDeepseekModelsText(), DEFAULT_DEEPSEEK_MODELS));
    }

    public List<String> parseGlmModels(SysUserSettings settings) {
        return readStringList(settings.getGlmModelsText(), DEFAULT_GLM_MODELS);
    }

    private UserSettingsResponse toResponse(SysUserSettings settings) {
        List<UserModelResponse> customModels = userModelRepository
                .findByUserIdOrderBySortOrderAscIdAsc(settings.getUserId()).stream()
                .map(this::toUserModelResponse)
                .toList();
        return UserSettingsResponse.from(settings,
                parseFilesystemRoots(settings),
                parseDisabledSkills(settings),
                parseDeepseekModels(settings),
                parseGlmModels(settings),
                customModels);
    }

    private SysUserSettings defaultSettings(Long userId) {
        return new SysUserSettings(
                userId,
                DEFAULT_PROVIDER,
                null,
                null,
                DEFAULT_DEEPSEEK_MODEL,
                DEFAULT_GLM_MODEL,
                null,
                writeJson(DEFAULT_FILESYSTEM_ROOTS),
                writeJson(List.of()),
                DEFAULT_TEMPERATURE,
                DEFAULT_MAX_STEPS,
                DEFAULT_RAG_ENABLED
        );
    }

    private String resolveDeepseekModel(String requestedModel, String fallbackModel) {
        String resolved = StringUtils.hasText(requestedModel) ? requestedModel.trim() : fallbackModel;
        if (DEFAULT_DEEPSEEK_MODELS.contains(resolved)) {
            return resolved;
        }
        if ("deepseek-chat".equals(resolved) || "deepseek-reasoner".equals(resolved)) {
            return DEFAULT_DEEPSEEK_MODEL;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "DeepSeek model must be deepseek-v4-flash or deepseek-v4-pro");
    }

    private List<String> normalizeDeepseekModels(List<String> models) {
        List<String> normalized = (models == null ? List.<String>of() : models).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(DEFAULT_DEEPSEEK_MODELS::contains)
                .distinct()
                .toList();
        return normalized.isEmpty() ? DEFAULT_DEEPSEEK_MODELS : normalized;
    }

    private String normalizeProvider(String provider, String fallback) {
        String resolved = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : fallback;
        if (!DEFAULT_PROVIDER.equals(resolved) && !PROVIDER_GLM.equals(resolved)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认模型提供方仅支持 deepseek 或 glm");
        }
        return resolved;
    }

    private String resolveDefaultProviderForUpdate(Long userId, String provider, String fallback) {
        if (!StringUtils.hasText(provider)) {
            return fallback;
        }
        String requested = provider.trim();
        String builtin = requested.toLowerCase();
        if (DEFAULT_PROVIDER.equals(builtin) || PROVIDER_GLM.equals(builtin)) {
            return builtin;
        }
        ensureUserInitialized(userId);
        boolean ownedModelProvider = userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .anyMatch(model -> model.getProviderKey().equals(requested));
        if (!ownedModelProvider) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Default model provider is not configured: " + requested);
        }
        return requested;
    }

    private void resetDefaultProviderIfNeeded(Long userId, String deletedProviderKey) {
        if (!StringUtils.hasText(deletedProviderKey)) {
            return;
        }
        repository.findById(userId)
                .filter(settings -> deletedProviderKey.equals(settings.getDefaultProvider()))
                .ifPresent(settings -> {
                    settings.update(DEFAULT_PROVIDER,
                            settings.getDeepseekApiKeyEncrypted(),
                            settings.getGlmApiKeyEncrypted(),
                            settings.getDeepseekModel(),
                            settings.getGlmModel(),
                            settings.getGithubPatEncrypted(),
                            settings.getFilesystemRootsText(),
                            settings.getDisabledSkillsJson(),
                            settings.getDeepseekTemperature(),
                            settings.getMaxSteps(),
                            settings.getRagDefaultEnabled(),
                            settings.getDeepseekModelsText(),
                            settings.getGlmModelsText());
                    repository.saveAndFlush(settings);
                });
    }

    private UserModelResponse toUserModelResponse(UserModel model) {
        boolean globalOpenRouterKey = isOpenRouterModel(model) && StringUtils.hasText(openRouterProperties.getApiKey());
        return UserModelResponse.from(model, globalOpenRouterKey);
    }

    private boolean isOpenRouterModel(UserModel model) {
        return model != null && model.getProviderKey() != null && model.getProviderKey().startsWith("openrouter-");
    }

    private String resolveEncryptedApiKey(String existingEncryptedValue, String apiKey) {
        if (apiKey == null) {
            return existingEncryptedValue;
        }
        if (apiKey.isBlank()) {
            return null;
        }
        return cryptoService.encrypt(apiKey.trim());
    }

    private List<String> readStringList(String json, List<String> fallback) {
        if (!StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化设置失败", ex);
        }
    }

    /** Resolved model endpoint for the agent harness. */
    public record ModelReference(String providerKey, String modelName) { }

    public record ModelEndpoint(String providerKey,
                                String modelName,
                                String apiUrl,
                                String apiKey,
                                String sourceType,
                                String sourceLabel) {
    }
}
