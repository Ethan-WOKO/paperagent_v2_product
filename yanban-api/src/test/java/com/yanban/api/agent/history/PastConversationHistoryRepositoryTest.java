package com.yanban.api.agent.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.history.PastConversationHistoryRepository.Position;
import com.yanban.api.project.Project;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurn;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import(PastConversationHistoryRepository.class)
class PastConversationHistoryRepositoryTest {
    @Autowired EntityManager em;
    @Autowired PastConversationHistoryRepository repository;
    private final ObjectMapper json = new ObjectMapper();
    private PastConversationHistoryService history;
    private static final Instant AT = Instant.parse("2026-09-09T02:00:00Z");

    @BeforeEach void service() { history = new PastConversationHistoryService(json, repository); }

    @Test
    void sqlQualifiesOwnerForMessagesSessionAndProjectAndExcludesInternalRoles() {
        AgentSession workspace = session(7, null);
        Project ownProject = project(7);
        AgentSession project = session(7, ownProject.getId());
        AgentSession foreignSession = session(8, null);
        AgentSession inaccessibleProject = session(7, project(8).getId());
        message(workspace, 7, "user", "workspace question");
        message(workspace, 7, "assistant", "workspace answer");
        message(project, 7, "assistant", "project answer");
        message(workspace, 8, "assistant", "wrong message owner");
        message(foreignSession, 7, "assistant", "wrong session owner");
        message(inaccessibleProject, 7, "assistant", "wrong project owner");
        message(workspace, 7, "tool", "private tool arguments");
        message(workspace, 7, "system", "private system prompt");
        message(workspace, 7, "assistant", "   ");

        assertThat(repository.window(7, null, null, Position.start(), 101))
                .extracting(PastConversationHistoryRepository.Row::content)
                .containsExactly("workspace question", "workspace answer", "project answer", "   ");
        var projected = history.search(7, null, null, null, 10);
        assertThat(projected.path("items").size()).isEqualTo(3);
        projected.path("items").forEach(item -> assertThat(item.path("text").asText()).isNotBlank());
        assertThat(repository.window(7, null, AgentSessionScope.PROJECT, Position.start(), 101))
                .extracting(PastConversationHistoryRepository.Row::content).containsExactly("project answer");
        assertThat(repository.session(8, workspace.getId())).isEmpty();
        assertThat(repository.session(7, inaccessibleProject.getId())).isEmpty();
    }

    @Test
    void searchesDeliveryOnlyKeywordAcrossWorkspaceAndProjectAndDeduplicatesSavedAssistant() {
        AgentSession workspace = session(7, null);
        AgentSession project = session(7, project(7).getId());
        delivery(workspace, "workspace-exclusive-keyword", 1);
        delivery(project, "project-exclusive-keyword", 1);
        delivery(project, "already materialized", 2);
        message(project, 7, "assistant", "already materialized");

        var result = history.search(7, "exclusive-keyword", null, null, 10);
        assertThat(result.path("items").size()).isEqualTo(2);
        result.path("items").forEach(item -> {
            assertThat(item.path("source").asText()).isEqualTo("reactplan_delivery");
            assertThat(item.path("text").asText()).contains("exclusive-keyword");
        });
        var duplicate = history.search(7, "already materialized", null, null, 10);
        assertThat(duplicate.path("resultCount").asInt()).isEqualTo(1);
        assertThat(duplicate.path("items").get(0).path("source").asText()).isEqualTo("message");
        assertThat(history.search(8, "exclusive-keyword", null, null, 10).path("resultCount").asInt()).isZero();
    }

    @Test
    void excludesDeliveryWithCrossBoundCheckpointOrTurnOrInstruction() {
        AgentSession own = session(7, null);
        String wrongCheckpoint = delivery(own, "checkpoint-secret", 1);
        String wrongTurn = delivery(own, "turn-secret", 2);
        String wrongInstruction = delivery(own, "message-secret", 3);
        em.createNativeQuery("update reactplan_task_checkpoints set user_id = 8 where task_id = :task")
                .setParameter("task", wrongCheckpoint).executeUpdate();
        em.createNativeQuery("update agent_turns set user_id = 8 where id = "
                        + "(select turn_id from reactplan_turn_intakes where task_id = :task)")
                .setParameter("task", wrongTurn).executeUpdate();
        em.createNativeQuery("update agent_messages set user_id = 8 where id = "
                        + "(select user_message_id from reactplan_turn_intakes where task_id = :task)")
                .setParameter("task", wrongInstruction).executeUpdate();
        em.clear();
        assertThat(history.search(7, "secret", null, null, 10).path("resultCount").asInt()).isZero();
    }

    @Test
    void deletedSessionsAndProjectsDoNotLeakOrphanedMessagesOrEventsAndDetailRechecks() {
        AgentSession removed = session(7, null);
        delivery(removed, "find before deletion", 1);
        var found = history.search(7, "find before deletion", null, null, 10).path("items").get(0);
        String cursor = found.path("detailCursor").asText();
        String sessionRef = found.path("sessionRef").asText();
        em.remove(removed);
        em.flush();
        assertThatThrownBy(() -> history.detail(7, sessionRef, cursor, 10))
                .isInstanceOf(PastConversationHistoryService.UnavailableException.class);
        assertThat(history.search(7, "find before deletion", null, null, 10).path("resultCount").asInt()).isZero();
        Project project = project(7);
        AgentSession orphanProject = session(7, project.getId());
        delivery(orphanProject, "orphan project", 2);
        em.remove(project);
        em.flush();
        assertThat(history.search(7, "orphan project", null, null, 10).path("resultCount").asInt()).isZero();
    }

    @Test
    void archiveSnapshotNeverGrantsAccessEvenWhileOriginalRowsRemain() {
        AgentSession archived = session(7, null);
        delivery(archived, "archived-only", 1);
        em.createNativeQuery("""
                insert into demo_chat_archive_sessions
                  (user_id, source_session_id, title, scope, model_provider_snapshot, model_snapshot,
                   session_created_at, session_updated_at, archived_at)
                values (:owner, :session, 'archived', 'WORKSPACE', 'provider', 'model', :at, :at, :at)
                """).setParameter("owner", 7L).setParameter("session", archived.getId())
                .setParameter("at", AT).executeUpdate();
        assertThat(repository.session(7, archived.getId())).isEmpty();
        assertThat(history.search(7, "archived-only", null, null, 10).path("resultCount").asInt()).isZero();
    }

    @Test
    void databaseKeysetMergesEqualTimestampsAndLongBodyContinuationWithoutLoss() {
        AgentSession session = session(7, null);
        message(session, 7, "assistant", "m1");
        delivery(session, "d1" + "x".repeat(4100), 1);
        message(session, 7, "assistant", "m2");
        delivery(session, "d2", 2);
        List<String> actual = new ArrayList<>();
        String cursor = null;
        int count = 0;
        do {
            var page = history.detail(7, "session." + session.getId(), cursor, 1);
            page.path("items").forEach(item -> actual.add(item.path("text").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null && ++count < 10);
        assertThat(cursor).isNull();
        assertThat(actual).containsExactly("m1", "request 1", "m2", "request 2",
                "d1" + "x".repeat(3998), "x".repeat(102), "d2");
    }

    @Test
    void databaseWindowAndResponseAreBoundedAndContinuePastNonmatchingRows() {
        AgentSession session = session(7, null);
        for (int i = 0; i < 102; i++) message(session, 7, "assistant", "entry " + i);
        assertThat(repository.window(7, session.getId(), null, Position.start(), 101)).hasSize(101);
        var first = history.search(7, "entry 101", null, null, 10);
        assertThat(first.path("scannedCount").asInt()).isEqualTo(100);
        assertThat(first.path("resultCount").asInt()).isZero();
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        var second = history.search(7, "entry 101", null, first.path("nextCursor").asText(), 10);
        assertThat(second.path("resultCount").asInt()).isEqualTo(1);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    void deduplicatesCompleteLongClobTextWithoutVarcharTruncationOrCrossOwnerMatches() {
        AgentSession session = session(7, null);
        String body = "x".repeat(8000) + "uniqueLongDeliverySuffix";
        delivery(session, body, 1);
        message(session, 7, "assistant", "  " + body + "  ");
        assertThat(repository.hasAssistantText(7, session.getId(), body)).isTrue();
        assertThat(repository.hasAssistantText(7, session.getId(), "x".repeat(8000) + "differentSuffix")).isFalse();
        assertThat(repository.hasAssistantText(8, session.getId(), body)).isFalse();
        var result = history.search(7, "uniqueLongDeliverySuffix", null, null, 10);
        assertThat(result.path("resultCount").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("source").asText()).isEqualTo("message");
    }

    @Test
    void blankAndNonDeliveryRowsAdvanceBoundedScanUntilLaterDeliveryMatch() {
        AgentSession session = session(7, null);
        for (int i = 0; i < 10; i++) message(session, 7, "assistant", "   ");
        String task = delivery(session, "late-delivery-keyword", 1);
        em.createNativeQuery("update reactplan_task_events set sequence_number = 101 where task_id = :task")
                .setParameter("task", task).executeUpdate();
        em.createNativeQuery("update reactplan_task_checkpoints set last_sequence = 101 where task_id = :task")
                .setParameter("task", task).executeUpdate();
        for (int sequence = 1; sequence <= 100; sequence++) {
            em.createNativeQuery("""
                    insert into reactplan_task_events (task_id, sequence_number, event_json, occurred_at)
                    values (:task, :sequence, :event, :at)
                    """).setParameter("task", task).setParameter("sequence", sequence)
                    .setParameter("event", "{\"type\":\"tool\",\"arguments\":\"late-delivery-keyword-private\"}")
                    .setParameter("at", LocalDateTime.ofInstant(AT, ZoneOffset.UTC)).executeUpdate();
        }
        var first = history.search(7, "late-delivery-keyword", null, null, 10);
        assertThat(first.path("scannedCount").asInt()).isEqualTo(100);
        assertThat(first.path("items").isEmpty()).isTrue();
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        var second = history.search(7, "late-delivery-keyword", null, first.path("nextCursor").asText(), 10);
        assertThat(second.path("resultCount").asInt()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("text").asText()).isEqualTo("late-delivery-keyword");
        assertThat(second.toString()).doesNotContain("private");
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    private Project project(long owner) {
        Project project = new Project(owner, "project", ".", ".", "[]", "[]");
        em.persist(project);
        em.flush();
        return project;
    }

    private AgentSession session(long owner, Long project) {
        AgentSession session = new AgentSession(owner, "session", "provider", "model", 8, true,
                project == null ? AgentSessionScope.WORKSPACE : AgentSessionScope.PROJECT, project);
        em.persist(session);
        em.flush();
        return session;
    }

    private AgentMessage message(AgentSession session, long owner, String role, String content) {
        AgentMessage message = new AgentMessage(session.getId(), owner, role, content,
                "{\"internal\":\"must never be projected\"}", null);
        em.persist(message);
        em.flush();
        em.createQuery("update AgentMessage m set m.createdAt = :at where m.id = :id")
                .setParameter("at", AT).setParameter("id", message.getId()).executeUpdate();
        return message;
    }

    /** Fixture writes use literal test-owned SQL; production only uses parameterized reads. */
    private String delivery(AgentSession session, String conclusion, int ordinal) {
        AgentMessage message = message(session, session.getUserId(), "user", "request " + ordinal);
        AgentTurn turn = new AgentTurn(session.getId(), session.getUserId(), message.getId());
        em.persist(turn);
        em.flush();
        String task = "task." + String.format("%064x", turn.getId());
        LocalDateTime at = LocalDateTime.ofInstant(AT, ZoneOffset.UTC);
        em.createNativeQuery("""
                insert into reactplan_turn_intakes (user_id, session_id, client_request_id, request_digest,
                    turn_id, user_message_id, task_id, created_at)
                values (:owner, :session, :task, :digest, :turn, :message, :task, :at)
                """).setParameter("owner", session.getUserId()).setParameter("session", session.getId())
                .setParameter("task", task).setParameter("digest", "a".repeat(64)).setParameter("turn", turn.getId())
                .setParameter("message", message.getId()).setParameter("at", at).executeUpdate();
        em.createNativeQuery("""
                insert into reactplan_task_checkpoints (task_id, request_digest, user_id, session_id, turn_id,
                    state, last_sequence, checkpoint_revision, checkpoint_json, created_at, updated_at,
                    usage_settled, settled_prompt_tokens, settled_completion_tokens, lease_fence, cancellation_requested)
                values (:task, :digest, :owner, :session, :turn, 'succeeded', 1, 1, '{}', :at, :at, false, 0, 0, 0, false)
                """).setParameter("task", task).setParameter("digest", "a".repeat(64))
                .setParameter("owner", session.getUserId()).setParameter("session", session.getId())
                .setParameter("turn", turn.getId()).setParameter("at", at).executeUpdate();
        em.createNativeQuery("""
                insert into reactplan_task_events (task_id, sequence_number, event_json, occurred_at)
                values (:task, 1, :event, :at)
                """).setParameter("task", task).setParameter("at", at)
                .setParameter("event", json.createObjectNode().put("type", "delivery")
                        .put("conclusion", conclusion).put("apiKey", "private-event-key").toString()).executeUpdate();
        return task;
    }
}
