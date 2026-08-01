package com.yanban.api.paper;

import com.yanban.api.agent.LangChain4jChatModelAdapter;
import com.yanban.api.agent.ModelInvocationContext;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.quota.UserQuotaService;
import com.yanban.paper.service.PaperModelExecutionContext;
import com.yanban.paper.service.PaperModelClient;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(PaperModelProperties.class)
public class PaperModelClientConfig {

    private static final String DEFAULT_PAPER_DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String DEFAULT_OPENROUTER_MODEL = "tencent/hy3:free";
    private static final String DEFAULT_DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_GLM_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String DEFAULT_OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";

    @Bean
    public PaperModelClient paperModelClient(LangChain4jChatModelAdapter chatModel,
                                             PaperModelProperties properties,
                                             ObjectProvider<UserSettingsService> userSettingsServiceProvider,
                                             UserQuotaService quotaService) {
        return paperModelClient(chatModel, properties, userSettingsServiceProvider.getIfAvailable(), quotaService);
    }

    PaperModelClient paperModelClient(LangChain4jChatModelAdapter chatModel,
                                      PaperModelProperties properties,
                                      UserSettingsService userSettingsService) {
        return paperModelClient(chatModel, properties, userSettingsService, null);
    }

    PaperModelClient paperModelClient(LangChain4jChatModelAdapter chatModel,
                                      PaperModelProperties properties,
                                      UserSettingsService userSettingsService,
                                      UserQuotaService quotaService) {
        Logger log = LoggerFactory.getLogger(PaperModelClientConfig.class);
        return (systemPrompt, userPrompt, temperature, maxTokens) -> {
            Long userId = PaperModelExecutionContext.currentUserId();
            if (quotaService != null && userId != null) {
                quotaService.assertCanUseAi(userId);
            }
            UserSettingsService.ModelEndpoint endpoint = resolveUserEndpoint(properties, userSettingsService);
            String modelName = endpoint == null ? resolveModel(properties) : endpoint.modelName();
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(defaultString(systemPrompt)),
                            UserMessage.from(defaultString(userPrompt))
                    ))
                    .parameters(ChatRequestParameters.builder()
                            .temperature(temperature)
                            .maxOutputTokens(maxTokens)
                            .build())
                    .modelName(modelName);
            ChatRequest request = builder.build();
            ChatResponse response;
            if (endpoint != null) {
                log.info("Paper model call provider={} model={} sourceType={} sourceLabel={}",
                        endpoint.providerKey(), endpoint.modelName(), endpoint.sourceType(), endpoint.sourceLabel());
                response = chatModel.chat(request, runtimeRequest(endpoint));
            } else if (StringUtils.hasText(properties.getProvider())) {
                log.info("Paper model call provider={} model={} sourceType=paper-properties sourceLabel=yanban.paper.model",
                        properties.getProvider(), modelName);
                response = chatModel.chat(request, runtimeRequest(properties));
            } else {
                log.info("Paper model call provider=default model={} sourceType=chat-model-bean sourceLabel=default", modelName);
                response = chatModel.chat(request);
            }
            if (quotaService != null && userId != null && response != null && response.tokenUsage() != null) {
                quotaService.recordUsage(userId, "PAPER", response.tokenUsage().inputTokenCount(),
                        response.tokenUsage().outputTokenCount(), response.tokenUsage().totalTokenCount());
            }
            return response == null || response.aiMessage() == null ? "" : defaultString(response.aiMessage().text());
        };
    }

    public PaperModelClient paperModelClient(LangChain4jChatModelAdapter chatModel, PaperModelProperties properties) {
        return paperModelClient(chatModel, properties, (UserSettingsService) null, null);
    }

    private UserSettingsService.ModelEndpoint resolveUserEndpoint(PaperModelProperties properties,
                                                                  UserSettingsService userSettingsService) {
        if (StringUtils.hasText(properties.getProvider()) || userSettingsService == null) {
            return null;
        }
        Long userId = PaperModelExecutionContext.currentUserId();
        if (userId == null) {
            return null;
        }
        return userSettingsService.resolveModelEndpoint(userId, null, null);
    }

    private ModelInvocationContext runtimeRequest(PaperModelProperties properties) {
        validateConfiguredPaperProvider(properties);
        return new ModelInvocationContext(
                normalizeProvider(properties.getProvider()),
                blankToNull(properties.getApiKey()),
                resolveApiUrl(properties),
                "paper-model-call"
        );
    }

    private void validateConfiguredPaperProvider(PaperModelProperties properties) {
        String provider = properties == null ? null : normalizeProvider(properties.getProvider());
        if (!StringUtils.hasText(provider) || "deepseek".equals(provider) || "glm".equals(provider)) {
            return;
        }
        if (!StringUtils.hasText(resolveModel(properties))) {
            throw new IllegalStateException("yanban.paper.model.model must be configured for provider " + provider);
        }
        if (!StringUtils.hasText(resolveApiUrl(properties))) {
            throw new IllegalStateException("yanban.paper.model.api-url must be configured for provider " + provider);
        }
        if (!StringUtils.hasText(blankToNull(properties.getApiKey()))) {
            throw new IllegalStateException("yanban.paper.model.api-key must be configured for provider " + provider
                    + " (set YANBAN_PAPER_MODEL_API_KEY)");
        }
    }

    private String resolveModel(PaperModelProperties properties) {
        if (properties == null) {
            return DEFAULT_PAPER_DEEPSEEK_MODEL;
        }
        if (StringUtils.hasText(properties.getModel())) {
            return properties.getModel().trim();
        }
        String provider = normalizeProvider(properties.getProvider());
        if (!StringUtils.hasText(provider) || "deepseek".equals(provider)) {
            return DEFAULT_PAPER_DEEPSEEK_MODEL;
        }
        if ("glm".equals(provider)) {
            return "glm-5.2";
        }
        if (provider.startsWith("openrouter")) {
            return DEFAULT_OPENROUTER_MODEL;
        }
        return "";
    }

    private String resolveApiUrl(PaperModelProperties properties) {
        if (properties == null) {
            return null;
        }
        if (StringUtils.hasText(properties.getApiUrl())) {
            return properties.getApiUrl().trim();
        }
        String provider = normalizeProvider(properties.getProvider());
        if ("deepseek".equals(provider)) {
            return DEFAULT_DEEPSEEK_API_URL;
        }
        if ("glm".equals(provider)) {
            return DEFAULT_GLM_API_URL;
        }
        if (StringUtils.hasText(provider) && provider.startsWith("openrouter")) {
            return DEFAULT_OPENROUTER_API_URL;
        }
        return null;
    }

    private String normalizeProvider(String provider) {
        return StringUtils.hasText(provider) ? provider.trim().toLowerCase() : null;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private ModelInvocationContext runtimeRequest(UserSettingsService.ModelEndpoint endpoint) {
        return new ModelInvocationContext(
                endpoint.providerKey(),
                endpoint.apiKey(),
                endpoint.apiUrl(),
                "paper-model-call"
        );
    }

    private static String defaultString(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
