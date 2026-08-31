package com.yanban.api.memory;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
class MemoryDistillationConversationService {
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final int MAX_MESSAGE_CHARACTERS = 4_000;

    private final AgentMessageRepository messages;
    private final AgentSessionRepository sessions;
    private final MemoryDistillationProperties properties;

    MemoryDistillationConversationService(AgentMessageRepository messages,
                                          AgentSessionRepository sessions,
                                          MemoryDistillationProperties properties) {
        this.messages = messages;
        this.sessions = sessions;
        this.properties = properties;
    }

    FrozenWindow freeze(long userId, long afterMessageId) {
        List<AgentMessage> candidates = messages.findDistillationWindow(
                userId, afterMessageId, ROLES, PageRequest.of(0, properties.getMessageBatchSize()));
        if (candidates.isEmpty()) return new FrozenWindow(afterMessageId, afterMessageId, 0);
        int remaining = properties.getMaxInputCharacters();
        int count = 0;
        int userCount = 0;
        long through = afterMessageId;
        for (AgentMessage message : candidates) {
            boolean userMessage = "user".equalsIgnoreCase(message.getRole());
            if (userMessage && userCount >= properties.getMaxCandidates()) break;
            String content = boundedContent(message.getContent());
            int cost = Math.max(1, content.length());
            if (count > 0 && cost > remaining) break;
            remaining -= Math.min(cost, remaining);
            through = message.getId();
            count++;
            if (userMessage) userCount++;
            if (remaining <= 0) break;
        }
        return new FrozenWindow(afterMessageId, through, count);
    }

    List<ConversationLine> load(long userId, long afterMessageId, long throughMessageId) {
        List<AgentMessage> source = messages.findDistillationWindow(
                userId, afterMessageId, throughMessageId, ROLES);
        Map<Long, AgentSession> bySession = new LinkedHashMap<>();
        sessions.findAllById(source.stream().map(AgentMessage::getSessionId).distinct().toList())
                .forEach(session -> bySession.put(session.getId(), session));
        return source.stream().map(message -> line(userId, message, bySession.get(message.getSessionId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ConversationLine line(long userId, AgentMessage message, AgentSession session) {
        if (session == null || !Long.valueOf(userId).equals(session.getUserId())) return null;
        String content = boundedContent(message.getContent());
        if (content.isBlank()) return null;
        boolean project = AgentSessionScope.PROJECT.equals(session.getScope()) && session.getProjectId() != null;
        return new ConversationLine(
                message.getId(), session.getId(), message.getRole(),
                project ? "PROJECT" : "USER", project ? session.getProjectId() : null,
                content, message.getCreatedAt());
    }

    private String boundedContent(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= MAX_MESSAGE_CHARACTERS
                ? normalized : normalized.substring(0, MAX_MESSAGE_CHARACTERS);
    }

    record FrozenWindow(long fromMessageId, long throughMessageId, int messageCount) {
        boolean hasWork() { return messageCount > 0 && throughMessageId > fromMessageId; }
    }

    record ConversationLine(long messageId, long sessionId, String role, String scope,
                            Long projectId, String content, Instant createdAt) { }
}
