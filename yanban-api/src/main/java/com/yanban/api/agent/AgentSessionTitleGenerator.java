package com.yanban.api.agent;

import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Shared title policy for ordinary and Project-scoped agent conversations. */
@Component
public class AgentSessionTitleGenerator {
    private static final Logger log = LoggerFactory.getLogger(AgentSessionTitleGenerator.class);
    private static final int GENERATED_TITLE_MAX_LENGTH = 40;

    private final ChatModelProvider modelProvider;
    private final UserSettingsService userSettings;

    public AgentSessionTitleGenerator(
            @Qualifier("chatModelProvider") ChatModelProvider modelProvider,
            UserSettingsService userSettings) {
        this.modelProvider = modelProvider;
        this.userSettings = userSettings;
    }

    public String generate(AgentSession session, Long userId, String firstUserMessage) {
        return generate(modelProvider, userSettings, session, userId, firstUserMessage);
    }

    public static String generate(
            ChatModelProvider modelProvider,
            UserSettingsService userSettings,
            AgentSession session,
            Long userId,
            String firstUserMessage) {
        try {
            UserSettingsService.ModelEndpoint endpoint = userSettings.resolveModelEndpoint(
                    userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
            ChatResponse response = modelProvider.chat(new ChatRequest(
                    endpoint.providerKey(),
                    endpoint.modelName(),
                    List.of(
                            ChatMessage.system("你是一个会话标题生成器。只输出标题，不要解释，不要标点，不要引号。中文不超过16个字，英文不超过8个词。"),
                            ChatMessage.user("请根据用户第一条消息生成简洁会话标题：\n" + firstUserMessage)
                    ),
                    0.2,
                    64,
                    null,
                    endpoint.apiKey(),
                    endpoint.apiUrl(),
                    null,
                    null,
                    null
            ));
            return sanitize(response == null || response.message() == null
                    ? null : response.message().content(), firstUserMessage);
        } catch (Exception ex) {
            log.warn("Failed to generate title for session id={}", session.getId(), ex);
            return fallback(firstUserMessage);
        }
    }

    public static boolean isDefaultTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return true;
        }
        String normalized = title.trim();
        return "新会话".equals(normalized) || "研伴对话".equals(normalized);
    }

    static String sanitize(String generated, String fallbackSource) {
        String title = StringUtils.hasText(generated) ? generated.trim() : fallback(fallbackSource);
        int lineBreak = title.indexOf('\n');
        if (lineBreak >= 0) {
            title = title.substring(0, lineBreak).trim();
        }
        title = title.replaceAll("^[\\s\\\"'“”‘’《》]+|[\\s\\\"'“”‘’《》]+$", "")
                .replaceAll("[。！？!?，,；;：:]+$", "")
                .trim();
        if (!StringUtils.hasText(title)) {
            title = fallback(fallbackSource);
        }
        return title.length() <= GENERATED_TITLE_MAX_LENGTH
                ? title : title.substring(0, GENERATED_TITLE_MAX_LENGTH).trim();
    }

    static String fallback(String firstUserMessage) {
        if (!StringUtils.hasText(firstUserMessage)) {
            return "新会话";
        }
        String normalized = firstUserMessage.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 16 ? normalized : normalized.substring(0, 16).trim();
    }
}
