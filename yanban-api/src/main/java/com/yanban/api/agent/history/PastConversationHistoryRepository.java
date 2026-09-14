package com.yanban.api.agent.history;

import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Fixed parameterized queries. Every source joins the currently accessible session and owner. */
@Repository
public class PastConversationHistoryRepository {
    private static final String ACCESS = """
            s.userId = :owner
            and ((s.scope = :workspace and s.projectId is null)
                 or (s.scope = :project and exists
                     (select p.id from Project p where p.id = s.projectId and p.userId = :owner)))
            and not exists (select a.id from DemoChatArchiveSession a where a.sourceSessionId = s.id)
            """;
    private static final String FILTER = """
            and (:sessionId is null or s.id = :sessionId)
            and (:scope is null or s.scope = :scope)
            """;
    private static final String MESSAGES = """
            select m.id, s.id, s.title, s.scope, s.projectId, m.role, m.content, m.createdAt
            from AgentMessage m, AgentSession s
            where m.sessionId = s.id and m.userId = :owner
            and m.role in ('user', 'assistant') and m.content is not null
            and
            """ + ACCESS + FILTER + """
            and (m.createdAt > :at or (m.createdAt = :at and
                (:source < 0 or (:source = 0 and
                    (m.id > :rowId or (m.id = :rowId and :inclusive = true))))))
            order by m.createdAt asc, m.id asc
            """;
    private static final String DELIVERIES = """
            select i.id, s.id, s.title, s.scope, s.projectId,
                   e.eventJson, e.occurredAt, e.sequenceNumber, i.taskId, m.content
            from ReactPlanTurnIntakeEntity i, ReactPlanTaskCheckpointEntity c,
                 ReactPlanTaskEventEntity e, AgentSession s, AgentMessage m, AgentTurn t
            where i.sessionId = s.id and i.userId = :owner
              and c.taskId = i.taskId and c.userId = :owner
              and c.sessionId = s.id and c.turnId = i.turnId
              and e.taskId = i.taskId and e.sequenceNumber <= c.lastSequence
              and m.id = i.userMessageId and m.userId = :owner and m.sessionId = s.id
              and m.role = 'user' and m.content is not null
              and t.id = i.turnId and t.userId = :owner and t.sessionId = s.id
              and t.userMessageId = m.id
              and
            """ + ACCESS + FILTER + """
            and (e.occurredAt > :at or (e.occurredAt = :at and
                (:source < 1 or (:source = 1 and (i.id > :rowId or
                    (i.id = :rowId and (e.sequenceNumber > :sequence or
                        (e.sequenceNumber = :sequence and :inclusive = true))))))))
            order by e.occurredAt asc, i.id asc, e.sequenceNumber asc
            """;

    private final EntityManager entityManager;

    public PastConversationHistoryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    Optional<AgentSession> session(long owner, long id) {
        return access(entityManager.createQuery(
                "select s from AgentSession s where s.id = :id and " + ACCESS, AgentSession.class), owner)
                .setParameter("id", id).getResultStream().findFirst();
    }

    boolean hasAssistantText(long owner, long sessionId, String content) {
        // Native TRIM operates on the complete MySQL LONGTEXT/H2 CLOB. HQL's STRING-only
        // TRIM validation rejects @Lob text; casting risks a default VARCHAR length limit.
        // Only a matching id is loaded, never an unbounded list of assistant bodies.
        return !entityManager.createNativeQuery("""
                select m.id from agent_messages m join agent_sessions s on m.session_id = s.id
                where m.user_id = :owner and s.user_id = :owner and s.id = :sessionId
                  and m.role = 'assistant' and trim(m.content) = :content
                  and ((s.scope = 'WORKSPACE' and s.project_id is null)
                    or (s.scope = 'PROJECT' and exists
                        (select p.id from projects p where p.id = s.project_id and p.user_id = :owner)))
                  and not exists (select a.id from demo_chat_archive_sessions a where a.source_session_id = s.id)
                """).setParameter("owner", owner).setParameter("sessionId", sessionId)
                .setParameter("content", content).setMaxResults(1).getResultList().isEmpty();
    }

    List<Row> window(long owner, Long sessionId, AgentSessionScope scope, Position after, int limit) {
        List<Row> rows = new ArrayList<>();
        TypedQuery<Object[]> messages = windowQuery(MESSAGES, owner, sessionId, scope, after, limit)
                .setParameter("at", after.at());
        for (Object[] r : messages.getResultList()) {
            rows.add(new Row(new Position((Instant) r[7], 0, (Long) r[0], 0, -1),
                    (Long) r[1], (String) r[2], (AgentSessionScope) r[3], (Long) r[4],
                    (String) r[5], (String) r[6], null));
        }
        TypedQuery<Object[]> deliveries = windowQuery(DELIVERIES, owner, sessionId, scope, after, limit)
                .setParameter("at", LocalDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                .setParameter("sequence", after.sequence());
        for (Object[] r : deliveries.getResultList()) {
            rows.add(new Row(new Position(((LocalDateTime) r[6]).toInstant(ZoneOffset.UTC),
                    1, (Long) r[0], (Long) r[7], -1), (Long) r[1], (String) r[2],
                    (AgentSessionScope) r[3], (Long) r[4], "assistant",
                    ((String) r[9]).isBlank() ? "" : (String) r[5], (String) r[8]));
        }
        return rows.stream().sorted(Comparator.comparing(Row::position, Position.ORDER))
                .limit(limit).toList();
    }

    private TypedQuery<Object[]> windowQuery(String query, long owner, Long sessionId,
                                             AgentSessionScope scope, Position after, int limit) {
        return access(entityManager.createQuery(query, Object[].class), owner)
                .setParameter("sessionId", sessionId).setParameter("scope", scope)
                .setParameter("source", after.source()).setParameter("rowId", after.id())
                .setParameter("inclusive", after.offset() >= 0).setMaxResults(limit);
    }

    private <T> TypedQuery<T> access(TypedQuery<T> query, long owner) {
        return query.setParameter("owner", owner)
                .setParameter("workspace", AgentSessionScope.WORKSPACE)
                .setParameter("project", AgentSessionScope.PROJECT);
    }

    record Position(Instant at, int source, long id, long sequence, int offset) {
        static final Comparator<Position> ORDER = Comparator.comparing(Position::at)
                .thenComparingInt(Position::source).thenComparingLong(Position::id)
                .thenComparingLong(Position::sequence);
        static Position start() { return new Position(Instant.EPOCH, 0, 0, 0, -1); }
        Position offset(int value) { return new Position(at, source, id, sequence, value); }
    }

    /** Internal row; event JSON is parsed and allowlisted before it can leave the service. */
    record Row(Position position, long sessionId, String title, AgentSessionScope scope,
               Long projectId, String role, String content, String taskId) { }
}
