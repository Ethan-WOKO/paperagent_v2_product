package com.yanban.api.demo;

import com.yanban.api.agent.reactplan.ReactPlanAdminConversationReader;
import com.yanban.api.user.SysUserRepository;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoChatArchiveService {

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final ReactPlanAdminConversationReader reactPlanConversations;
    private final DemoChatArchiveSessionRepository archiveSessions;
    private final DemoChatArchiveMessageRepository archiveMessages;
    private final SysUserRepository users;

    public DemoChatArchiveService(AgentSessionRepository sessions,
                                  AgentMessageRepository messages,
                                  ReactPlanAdminConversationReader reactPlanConversations,
                                  DemoChatArchiveSessionRepository archiveSessions,
                                  DemoChatArchiveMessageRepository archiveMessages,
                                  SysUserRepository users) {
        this.sessions = sessions;
        this.messages = messages;
        this.reactPlanConversations = reactPlanConversations;
        this.archiveSessions = archiveSessions;
        this.archiveMessages = archiveMessages;
        this.users = users;
    }

    @Transactional
    public void archiveCurrentSessions(Long userId) {
        Instant archivedAt = Instant.now();
        for (AgentSession session : sessions.findByUserIdOrderByUpdatedAtDesc(userId)) {
            if (archiveSessions.existsBySourceSessionId(session.getId())) {
                continue;
            }
            DemoChatArchiveSession archived = archiveSessions.saveAndFlush(
                    new DemoChatArchiveSession(session, archivedAt));
            archiveMessages.saveAll(messageDrafts(userId, session).stream()
                    .map(message -> new DemoChatArchiveMessage(
                            archived.getId(), message.sourceMessageId(), message.role(), message.content(),
                            message.createdAt(), message.deletable()))
                    .toList());
        }
        archiveMessages.flush();
    }

    @Transactional(readOnly = true)
    public List<ArchivedChat> list(Long userId) {
        List<DemoChatArchiveSession> found = archiveSessions.findByUserIdOrderBySessionUpdatedAtDesc(userId);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ArchivedMessage>> bySession = new LinkedHashMap<>();
        Collection<Long> sessionIds = found.stream().map(DemoChatArchiveSession::getId).toList();
        for (DemoChatArchiveMessage message : archiveMessages
                .findByArchiveSessionIdInOrderByArchiveSessionIdAscMessageCreatedAtAscIdAsc(sessionIds)) {
            bySession.computeIfAbsent(message.getArchiveSessionId(), ignored -> new ArrayList<>())
                    .add(new ArchivedMessage(
                            message.getId(), message.getRole(), message.getContent(),
                            message.getMessageCreatedAt(), Boolean.TRUE.equals(message.getDeletable())));
        }
        return found.stream()
                .map(session -> new ArchivedChat(
                        session.getId(), session.getSourceSessionId(), session.getTitle(), session.getScope(),
                        session.getSourceProjectId(), session.getModelProviderSnapshot(), session.getModelSnapshot(),
                        session.getSessionCreatedAt(), session.getSessionUpdatedAt(), session.getArchivedAt(),
                        List.copyOf(bySession.getOrDefault(session.getId(), List.of()))))
                .sorted(Comparator.comparing(ArchivedChat::updatedAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(Long userId) {
        return archiveSessions.countByUserId(userId);
    }

    @Transactional
    public void deleteMessage(Long archiveMessageId) {
        DemoChatArchiveMessage message = archiveMessages.findById(archiveMessageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "游客历史消息不存在"));
        DemoChatArchiveSession session = archiveSessions.findById(message.getArchiveSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "游客历史会话不存在"));
        boolean demo = users.findById(session.getUserId())
                .map(user -> DemoAccessService.ACCOUNT_TYPE_DEMO.equalsIgnoreCase(user.getAccountType()))
                .orElse(false);
        if (!demo || !Boolean.TRUE.equals(message.getDeletable())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能删除可删除的游客历史消息");
        }
        archiveMessages.delete(message);
    }

    @Transactional
    public void clear(Long userId) {
        archiveSessions.deleteAll(archiveSessions.findByUserIdOrderBySessionUpdatedAtDesc(userId));
        archiveSessions.flush();
    }

    private List<MessageDraft> messageDrafts(Long userId, AgentSession session) {
        if (session.getScope() == AgentSessionScope.PROJECT) {
            List<MessageDraft> projected = reactPlanConversations.read(userId, session.getId()).stream()
                    .map(message -> new MessageDraft(
                            null, message.role(), message.content(), message.createdAt(), false))
                    .toList();
            if (!projected.isEmpty()) {
                return projected;
            }
        }
        return messages.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(this::ordinaryMessage)
                .toList();
    }

    private MessageDraft ordinaryMessage(AgentMessage message) {
        return new MessageDraft(
                message.getId(), message.getRole(), message.getContent(), message.getCreatedAt(), true);
    }

    public record ArchivedChat(Long id,
                               Long sourceSessionId,
                               String title,
                               String scope,
                               Long projectId,
                               String modelProvider,
                               String model,
                               Instant createdAt,
                               Instant updatedAt,
                               Instant archivedAt,
                               List<ArchivedMessage> messages) {
    }

    public record ArchivedMessage(Long id, String role, String content, Instant createdAt, boolean deletable) {
    }

    private record MessageDraft(Long sourceMessageId,
                                String role,
                                String content,
                                Instant createdAt,
                                boolean deletable) {
    }
}
