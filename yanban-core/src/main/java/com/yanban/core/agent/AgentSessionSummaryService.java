package com.yanban.core.agent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionSummaryService {

    private final AgentSessionSummaryRepository summaries;
    private final EntityManager entityManager;

    public AgentSessionSummaryService(AgentSessionSummaryRepository summaries) {
        this(summaries, null);
    }

    @Autowired
    public AgentSessionSummaryService(
            AgentSessionSummaryRepository summaries,
            EntityManager entityManager) {
        this.summaries = summaries;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Optional<AgentSessionSummary> findBySessionAndUser(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return Optional.empty();
        }
        return summaries.findBySessionIdAndUserId(sessionId, userId);
    }

    @Transactional
    public AgentSessionSummary upsert(AgentSessionSummaryUpdate update) {
        lockOwnedSession(update);
        validateCoverageMessage(update);
        Optional<AgentSessionSummary> existing = entityManager == null
                ? summaries.findBySessionIdAndUserId(
                        update.sessionId(), update.userId())
                : summaries.findLockedBySessionIdAndUserId(
                        update.sessionId(), update.userId());
        AgentSessionSummary summary;
        if (existing.isPresent()) {
            summary = existing.get();
            requireMonotonic(summary, update);
            summary.update(
                    update.summaryText(),
                    update.coveredMessageId(),
                    update.messageCount(),
                    update.modelProviderSnapshot(),
                    update.modelSnapshot()
            );
        } else {
            summary = new AgentSessionSummary(
                        update.sessionId(),
                        update.userId(),
                        update.summaryText(),
                        update.coveredMessageId(),
                        update.messageCount(),
                        update.modelProviderSnapshot(),
                        update.modelSnapshot()
            );
        }
        return summaries.saveAndFlush(summary);
    }

    private void lockOwnedSession(AgentSessionSummaryUpdate update) {
        if (entityManager == null) return;
        AgentSession session = entityManager.find(
                AgentSession.class, update.sessionId(),
                LockModeType.PESSIMISTIC_WRITE);
        if (session == null || !update.userId().equals(session.getUserId())) {
            throw new IllegalArgumentException("summary session authority is invalid");
        }
    }

    private void validateCoverageMessage(AgentSessionSummaryUpdate update) {
        if (entityManager == null || update.coveredMessageId() == null) return;
        AgentMessage message = entityManager.find(
                AgentMessage.class, update.coveredMessageId());
        if (message == null
                || !update.userId().equals(message.getUserId())
                || !update.sessionId().equals(message.getSessionId())
                || !"assistant".equalsIgnoreCase(message.getRole())) {
            throw new IllegalArgumentException(
                    "summary coverage message authority is invalid");
        }
    }

    private static void requireMonotonic(
            AgentSessionSummary existing,
            AgentSessionSummaryUpdate update) {
        Long covered = existing.getCoveredMessageId();
        Long requested = update.coveredMessageId();
        if (covered != null && requested == null) {
            throw new IllegalArgumentException(
                    "summary coverage cannot return to unknown");
        }
        if (covered != null && requested < covered) {
            throw new IllegalArgumentException(
                    "summary coverage cannot decrease");
        }
        int requestedCount = update.messageCount() == null
                ? 0 : update.messageCount();
        if (requestedCount < existing.getMessageCount()) {
            throw new IllegalArgumentException(
                    "summary messageCount cannot decrease");
        }
    }

    @Transactional
    public void deleteBySession(Long sessionId) {
        if (sessionId != null) {
            summaries.deleteBySessionId(sessionId);
        }
    }
}
