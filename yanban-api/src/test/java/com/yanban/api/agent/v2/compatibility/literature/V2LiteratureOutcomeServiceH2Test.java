package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2_literature_outcomes;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        V2LiteratureOutcomeService.class,
        V2LiteratureOutcomeServiceH2Test.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class V2LiteratureOutcomeServiceH2Test {
    @TestConfiguration
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
    @Autowired
    V2LiteratureOutcomeService service;
    @Autowired
    AgentSessionRepository sessions;
    @Autowired
    LiteratureDeliveryJpaRepository deliveries;
    @Autowired
    LiteratureSearchTaskRepository tasks;
    @Autowired
    AgentMessageRepository messages;
    @Autowired
    ObjectMapper json;
    @MockBean
    LiteratureSearchTaskService taskService;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        deliveries.deleteAll();
        messages.deleteAll();
        tasks.deleteAll();
        sessions.deleteAll();
        sessionId = sessions.saveAndFlush(new AgentSession(
                7L, "workspace", "mock", "model", 4, false)).getId();
    }

    @Test
    void exposesBoundedRealResultAndOmitsBibtexWhenNotRequested() {
        LiteratureSearchTask task = completedTask(false, false);
        delivery("result", task, false);

        V2LiteratureOutcomeResponse result =
                service.get(7L, sessionId, "result");

        assertEquals("COMPLETED", result.status());
        assertTrue(result.terminal());
        assertFalse(result.cancellable());
        assertEquals(1, result.resultCount());
        assertEquals(1, result.items().size());
        assertNull(result.items().get(0).bibtex());
        assertNull(result.items().get(0).url());
        assertNotNull(result.resultMessageId());
        String content = messages.findById(result.resultMessageId())
                .orElseThrow().getContent();
        assertTrue(content.contains("Safe title"));
        assertFalse(content.contains("javascript:"));
    }

    @Test
    void resultItemsAreCappedAtFrozenRequestedTopK() {
        LiteratureSearchTask task = task("COMPLETED", false);
        task.setCurrentStage("COMPLETE");
        task.setUniqueCandidateCount(12);
        String items = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> "{\"title\":\"Paper " + index + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        task.setResultJson("{\"items\":[" + items
                + "],\"sourceFailures\":[]}");
        tasks.saveAndFlush(task);
        delivery("bounded", task, false);

        V2LiteratureOutcomeResponse result =
                service.get(7L, sessionId, "bounded");

        assertEquals(10, result.requestedTopK());
        assertEquals(10, result.resultCount());
        assertEquals(10, result.items().size());
        assertEquals(12, result.totalCount());
    }

    @Test
    void partialResultIsTruthfulAndSerialReplayUsesSameMessage() {
        LiteratureSearchTask task = completedTask(true, true);
        delivery("partial", task, true);

        V2LiteratureOutcomeResponse first =
                service.get(7L, sessionId, "partial");
        V2LiteratureOutcomeResponse replay =
                service.get(7L, sessionId, "partial");

        assertEquals("PARTIAL", first.status());
        assertEquals(List.of("OpenAlex source unavailable"),
                first.sourceFailures());
        assertEquals("@article{safe}", first.items().get(0).bibtex());
        assertEquals(first.resultMessageId(), replay.resultMessageId());
    }

    @Test
    void providerFailuresExposeOnlyStableSourceLevelHints() {
        LiteratureSearchTask task = completedTask(false, false);
        task.setSourceFailuresJson("""
                [
                  "openalex: GET http://10.0.0.7/private?token=secret failed",
                  "arxiv: java.net.ConnectException at internal-host:9200",
                  "provider password=hunter2 stack trace"
                ]
                """);
        tasks.saveAndFlush(task);
        delivery("safe-failures", task, false);

        V2LiteratureOutcomeResponse result =
                service.get(7L, sessionId, "safe-failures");

        assertEquals(List.of(
                "OpenAlex source unavailable",
                "arXiv source unavailable",
                "Literature source unavailable"),
                result.sourceFailures());
        String serialized = result.sourceFailures().toString();
        assertFalse(serialized.contains("10.0.0.7"));
        assertFalse(serialized.contains("secret"));
        assertFalse(serialized.contains("internal-host"));
        assertFalse(serialized.contains("hunter2"));
    }

    @Test
    void eightConcurrentReadsCreateExactlyOneResultMessage() throws Exception {
        LiteratureSearchTask task = completedTask(false, false);
        delivery("race", task, false);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<V2LiteratureOutcomeResponse>> calls =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(index ->
                                    (Callable<V2LiteratureOutcomeResponse>) () ->
                                            service.get(
                                                    7L, sessionId, "race"))
                            .toList();
            List<Long> ids = pool.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get().resultMessageId();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertEquals(1, ids.stream().distinct().count());
            assertEquals(1, messages.findBySessionIdOrderByCreatedAtAsc(
                    sessionId).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pendingRunningFailureAndCancellationStatesDoNotInventPapers() {
        for (String status : List.of(
                "PENDING", "RUNNING", "CANCEL_REQUESTED", "CANCELLING",
                "FAILED", "CANCELLED", "STOPPED")) {
            LiteratureSearchTask task = task(status, false);
            String key = status.toLowerCase();
            delivery(key, task, false);
            V2LiteratureOutcomeResponse result =
                    service.get(7L, sessionId, key);
            assertTrue(result.items().isEmpty());
            assertNull(result.resultMessageId());
            if (status.equals("STOPPED")) {
                assertEquals("CANCELLED", result.status());
                assertTrue(result.terminal());
            }
        }
    }

    @Test
    void unauthorizedProjectCrossSessionAndCrossKeyAreIndistinguishable() {
        LiteratureSearchTask task = task("PENDING", false);
        delivery("owned", task, false);
        Long projectSession = sessions.saveAndFlush(new AgentSession(
                7L, "project", "mock", "model", 4, false,
                AgentSessionScope.PROJECT, 44L)).getId();
        for (ThrowingCall call : List.of(
                (ThrowingCall) () ->
                        service.get(8L, sessionId, "owned"),
                (ThrowingCall) () ->
                        service.get(7L, sessionId + 1000, "owned"),
                (ThrowingCall) () ->
                        service.get(7L, sessionId, "other"),
                (ThrowingCall) () ->
                        service.get(7L, projectSession, "owned"))) {
            ResponseStatusException failure = assertThrows(
                    ResponseStatusException.class, call::run);
            assertEquals(404, failure.getStatusCode().value());
        }
    }

    @Test
    void corruptOrOversizedResultFailsWithoutMessage() {
        LiteratureSearchTask corrupt = task("COMPLETED", false);
        corrupt.setResultJson("{not-json");
        tasks.saveAndFlush(corrupt);
        delivery("corrupt", corrupt, false);
        assertEquals(409, assertThrows(
                ResponseStatusException.class,
                () -> service.get(7L, sessionId, "corrupt"))
                .getStatusCode().value());
        assertTrue(messages.findBySessionIdOrderByCreatedAtAsc(
                sessionId).isEmpty());
    }

    @Test
    void cancelUsesOnlyBoundTaskAndCompletionRaceStillDeliversResult() {
        LiteratureSearchTask task = completedTask(false, false);
        delivery("cancel-race", task, false);
        when(taskService.requestCancel(7L, task.getId(),
                "Cancelled from V2 literature turn")).thenReturn(task);

        V2LiteratureOutcomeResponse response =
                service.cancel(7L, sessionId, "cancel-race");

        verify(taskService).requestCancel(
                7L, task.getId(), "Cancelled from V2 literature turn");
        assertEquals("COMPLETED", response.status());
        assertNotNull(response.resultMessageId());
    }

    private LiteratureSearchTask completedTask(
            boolean sourceFailure, boolean includeBibtex) {
        LiteratureSearchTask task = task("COMPLETED", includeBibtex);
        task.setCurrentStage("COMPLETE");
        task.setUniqueCandidateCount(1);
        task.setResultJson("""
                {"items":[{
                  "cardId":33,
                  "title":"Safe title\\nwith control",
                  "authors":["Alice","Bob"],
                  "year":2025,
                  "venue":"Venue",
                  "doi":"10.1/test",
                  "arxivId":"2501.1",
                  "openAlexId":"W1",
                  "url":"javascript:alert(1)",
                  "source":"openalex",
                  "score":0.9,
                  "bibtex":"@article{safe}"
                }],"sourceFailures":[]}
                """);
        task.setSourceFailuresJson(sourceFailure
                ? "[\"openalex: unavailable\"]" : "[]");
        return tasks.saveAndFlush(task);
    }

    private LiteratureSearchTask task(String status, boolean includeBibtex) {
        LiteratureSearchTask task = new LiteratureSearchTask(
                7L, null, "graph agents", "graph agents",
                10, 2024, includeBibtex, status, "QUEUED",
                "task-request-" + status + "-" + System.nanoTime(),
                "idem-" + System.nanoTime());
        return tasks.saveAndFlush(task);
    }

    private LiteratureDeliveryEntity delivery(
            String key, LiteratureSearchTask task, boolean includeBibtex) {
        LiteratureDeliveryEntity delivery = new LiteratureDeliveryEntity(
                new LiteratureDeliveryKey(7L, sessionId, key),
                "a".repeat(64), "graph agents", 10, 2024,
                includeBibtex, 101L, 202L,
                "owner", "token", Instant.now().plusSeconds(60),
                Instant.now());
        delivery.bindLiteratureTask(task.getId());
        return deliveries.saveAndFlush(delivery);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
