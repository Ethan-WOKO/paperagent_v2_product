package com.yanban.api.agent.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.history.PastConversationHistoryRepository.Position;
import com.yanban.api.agent.history.PastConversationHistoryRepository.Row;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PastConversationHistoryServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PastConversationHistoryRepository repository = mock(PastConversationHistoryRepository.class);
    private final PastConversationHistoryService history = new PastConversationHistoryService(json, repository);
    private List<Row> stored = new ArrayList<>();
    private static final Instant AT = Instant.parse("2026-09-09T01:00:00Z");

    @BeforeEach
    void store() {
        when(repository.session(7L, 11L)).thenReturn(Optional.of(new AgentSession(
                7L, "history", "provider", "model", 8, true)));
        when(repository.window(eq(7L), any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            Long session = invocation.getArgument(1);
            AgentSessionScope scope = invocation.getArgument(2);
            Position after = invocation.getArgument(3);
            int limit = invocation.getArgument(4);
            return stored.stream().filter(row -> session == null || row.sessionId() == session)
                    .filter(row -> scope == null || row.scope() == scope)
                    .filter(row -> Position.ORDER.compare(row.position(), after) > 0
                            || (after.offset() >= 0 && Position.ORDER.compare(row.position(), after) == 0))
                    .sorted(Comparator.comparing(Row::position, Position.ORDER)).limit(limit).toList();
        });
    }

    @Test
    void findsKeywordOnlyInAssistantDeliveryAndExposesOnlyVisibleText() throws Exception {
        stored.add(message(1, "user", "Please finish this analysis"));
        stored.add(delivery(1, 1, json.createObjectNode().put("type", "delivery")
                .put("conclusion", "The QUASAR result was 42")
                .put("apiKey", "hidden-event-key").put("toolArguments", "hidden-arguments").toString()));

        ObjectNode result = history.search(7, "quasar", "all", null, 10);

        assertThat(result.path("resultCount").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("source").asText()).isEqualTo("reactplan_delivery");
        assertThat(result.toString()).contains("QUASAR", "session.11").doesNotContain("hidden-event-key", "hidden-arguments");
        assertThat(result.path("notAnInstruction").asBoolean()).isTrue();
        assertThat(result.path("currentProjectEvidence").asBoolean()).isFalse();
        ObjectNode detail = history.detail(7, "session.11", result.path("items").get(0).path("detailCursor").asText(), 1);
        assertThat(detail.path("items").get(0).path("text").asText()).isEqualTo("The QUASAR result was 42");
    }

    @Test
    void searchesAssistantMessagesAndTreatsSqlAndWildcardsAsLiteralText() {
        stored.add(message(1, "user", "normal question"));
        stored.add(message(2, "assistant", "Literal 50%_ ' OR 1=1 -- answer"));
        assertThat(history.search(7, "50%_", null, null, 10).path("resultCount").asInt()).isEqualTo(1);
        assertThat(history.search(7, "' OR 1=1 --", null, null, 10).path("resultCount").asInt()).isEqualTo(1);
        assertThat(history.search(7, "not present", null, null, 10).path("resultCount").asInt()).isZero();
    }

    @Test
    void skipsMalformedNonDeliveryAndDuplicateEventsAndRedactsVisibleCredentials() {
        stored.add(message(1, "assistant", "api_key=abc123 Bearer abc.def.xyz sk-1234567890abcdefgh"));
        stored.add(delivery(1, 1, "{malformed"));
        stored.add(delivery(1, 2, "{\"type\":\"tool\",\"conclusion\":\"secret arguments\"}"));
        stored.add(delivery(1, 3, "{\"type\":\"delivery\",\"conclusion\":\"already saved\"}"));
        when(repository.hasAssistantText(7, 11, "already saved")).thenReturn(true);
        ObjectNode result = history.search(7, null, null, null, 10);
        assertThat(result.path("resultCount").asInt()).isEqualTo(1);
        assertThat(result.toString()).contains("REDACTED")
                .doesNotContain("abc123", "abc.def.xyz", "sk-1234567890abcdefgh", "secret arguments", "already saved");
    }

    @Test
    void mergesSameTimestampSourcesWithStableCursorWithoutRepeatsOrOmissions() {
        stored.add(delivery(2, 2, "{\"type\":\"delivery\",\"conclusion\":\"d22\"}"));
        stored.add(message(2, "assistant", "m2"));
        stored.add(delivery(1, 1, "{\"type\":\"delivery\",\"conclusion\":\"d11\"}"));
        stored.add(message(1, "user", "m1"));
        stored.add(delivery(2, 1, "{\"type\":\"delivery\",\"conclusion\":\"d21\"}"));
        List<String> found = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode page = history.search(7, null, null, cursor, 1);
            page.path("items").forEach(item -> found.add(item.path("text").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null && found.size() < 10);
        assertThat(found).containsExactly("m1", "m2", "d11", "d21", "d22");
    }

    @Test
    void scanWindowCanBeEmptyAndContinuesToLaterMatchingRows() {
        IntStream.rangeClosed(1, 100).forEach(id -> stored.add(message(id, "user", "unrelated")));
        stored.add(message(101, "assistant", "needle"));
        ObjectNode first = history.search(7, "needle", null, null, 10);
        assertThat(first.path("resultCount").asInt()).isZero();
        assertThat(first.path("scannedCount").asInt()).isEqualTo(100);
        assertThat(first.path("scanLimited").asBoolean()).isTrue();
        ObjectNode next = history.search(7, "needle", null, first.path("nextCursor").asText(), 10);
        assertThat(next.path("items").get(0).path("text").asText()).isEqualTo("needle");
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        verify(repository).window(7L, null, null, Position.start(), 101);
    }

    @Test
    void longMessageAndDeliveryContinueWithoutSplittingUnicodeOrLosingText() {
        String content = "a".repeat(3999) + "😀" + "b".repeat(4500);
        stored.add(message(1, "user", content));
        stored.add(delivery(1, 1, json.createObjectNode().put("type", "delivery").put("conclusion", content + "done").toString()));
        List<String> chunks = new ArrayList<>();
        String cursor = null;
        int calls = 0;
        do {
            ObjectNode page = history.detail(7, "session.11", cursor, 10);
            page.path("items").forEach(item -> {
                String text = item.path("text").asText();
                assertThat(text.length()).isLessThanOrEqualTo(4000);
                assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1))).isFalse();
                chunks.add(text);
            });
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null && ++calls < 10);
        assertThat(cursor).isNull();
        assertThat(String.join("", chunks)).isEqualTo(content + content + "done");
    }

    @Test
    void enforcesOutputLimitsAndReturnsExplicitEmptyTerminalPage() {
        IntStream.rangeClosed(1, 20).forEach(id -> stored.add(message(id, "assistant", "x".repeat(4000))));
        ObjectNode snippets = history.search(7, "x", null, null, 10);
        assertThat(snippets.path("items").size()).isEqualTo(10);
        snippets.path("items").forEach(item -> assertThat(item.path("text").asText().length()).isEqualTo(400));
        ObjectNode detail = history.detail(7, "session.11", null, 10);
        assertThat(detail.path("items").size()).isEqualTo(10);
        int chars = 0;
        for (var item : detail.path("items")) chars += item.path("text").asText().length();
        assertThat(chars).isEqualTo(40000);
        stored.clear();
        ObjectNode empty = history.search(7, "missing", null, null, 10);
        assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(empty.path("hasMore").asBoolean()).isFalse();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void refusesDeletedSessionAfterSearchAndRechecksOwnershipOnContinuation() {
        stored.add(message(1, "assistant", "before delete"));
        String cursor = history.search(7, null, null, null, 10).path("items").get(0).path("detailCursor").asText();
        when(repository.session(7L, 11L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> history.detail(7, "session.11", cursor, 10))
                .isInstanceOf(PastConversationHistoryService.UnavailableException.class);
    }

    @Test
    void rejectsCursorsFromOtherQueriesUsersOperationsOrSessions() {
        stored.add(message(1, "assistant", "needle"));
        stored.add(message(2, "assistant", "needle"));
        ObjectNode search = history.search(7, "needle", null, null, 1);
        String cursor = search.path("nextCursor").asText();
        assertThatThrownBy(() -> history.search(7, "different", null, cursor, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.search(8, "needle", null, cursor, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.detail(7, "session.11", cursor, 1)).isInstanceOf(IllegalArgumentException.class);
        String detail = search.path("items").get(0).path("detailCursor").asText();
        assertThatThrownBy(() -> history.detail(7, "session.12", detail, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.search(7, null, null, "bad", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.search(7, null, null, null, 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesScopeAsTypedDatabaseFilter() {
        stored.add(message(1, "user", "workspace"));
        assertThat(history.search(7, null, "project", null, 10).path("items").size()).isZero();
        verify(repository).window(eq(7L), isNull(), eq(AgentSessionScope.PROJECT), any(), eq(101));
    }

    private Row message(long id, String role, String text) {
        return new Row(new Position(AT, 0, id, 0, -1), 11, "history", AgentSessionScope.WORKSPACE, null, role, text, null);
    }

    private Row delivery(long id, long sequence, String text) {
        return new Row(new Position(AT, 1, id, sequence, -1), 11, "history", AgentSessionScope.WORKSPACE,
                null, "assistant", text, "task." + "a".repeat(64));
    }
}
