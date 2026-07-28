package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartComposer;
import com.yanban.api.agent.v2.loop.AuthenticatedPersistentPlanAgentLoopComposer;
import com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopOutcome;
import com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopState;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.execution.start.FreshExecutionRecoveryRequired;
import io.paperagent.v2.runtime.synthesis.DefaultFinalSynthesisComposer;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisCompositionResult;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisDisposition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({
        LiteratureDeliveryTransactions.class,
        V2LiteratureTurnService.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class V2LiteratureTurnH2VerticalTest {
    @Autowired
    AgentSessionRepository sessions;
    @Autowired
    V2LiteratureTurnService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LiteratureDeliveryTransactions transactions;
    @MockBean
    AuthenticatedAgentTurnFreshExecutionStartComposer starts;
    @MockBean
    AuthenticatedPersistentPlanAgentLoopComposer loop;
    @MockBean
    StepRecoveryRepository recovery;
    @MockBean
    DefaultFinalSynthesisComposer synthesis;
    @MockBean
    io.paperagent.v2.persistence.LeaseRepository leases;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void exactReplayAndEightConcurrentCallsDeliverOneMessageSet() throws Exception {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "workspace", "mock", "model", 4, false));
        PlanId planId = new PlanId("plan-vertical");
        PersistedStepRecoverySucceeded terminal =
                org.mockito.Mockito.mock(PersistedStepRecoverySucceeded.class);
        when(terminal.taskFrame()).thenReturn(org.mockito.Mockito.mock(
                io.paperagent.v2.contracts.TaskFrame.class));
        when(terminal.plan()).thenReturn(org.mockito.Mockito.mock(
                io.paperagent.v2.contracts.Plan.class));
        var versioned = org.mockito.Mockito.mock(
                io.paperagent.v2.persistence.VersionedCheckpoint.class);
        when(versioned.checkpoint()).thenReturn(org.mockito.Mockito.mock(
                io.paperagent.v2.contracts.Checkpoint.class));
        when(terminal.checkpoint()).thenReturn(versioned);
        when(starts.start(eq(7L), any(), any()))
                .thenReturn(new FreshExecutionRecoveryRequired(planId));
        when(loop.execute(eq(7L), any(), any())).thenReturn(
                new PersistentPlanAgentLoopOutcome(
                        planId, 1,
                        PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                        Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty()));
        when(recovery.inspect(planId))
                .thenReturn(PersistenceResult.found(terminal));
        FinalSynthesis finalValue = new FinalSynthesis(
                new FinalSynthesisId("synthesis-vertical"),
                new TaskFrameId("task-vertical"),
                planId, new PlanRevisionId("revision-vertical"),
                Optional.empty(), Optional.empty(),
                List.of(new ReceiptId("receipt-vertical")),
                "Literature search task queued.",
                Instant.parse("2026-07-28T00:00:00Z"));
        when(synthesis.compose(any())).thenReturn(
                new FinalSynthesisCompositionResult(
                        finalValue, FinalSynthesisDisposition.APPLIED));
        V2LiteratureTurnRequest request = new V2LiteratureTurnRequest(
                "restart-safe agents", 10, 2024, true, "vertical-77");

        V2LiteratureTurnResponse first =
                service.execute(7L, session.getId(), request);
        assertSameDelivery(
                first, service.execute(7L, session.getId(), request));

        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<V2LiteratureTurnResponse>> calls =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(index ->
                                    (Callable<V2LiteratureTurnResponse>) () ->
                                            service.execute(
                                                    7L, session.getId(), request))
                            .toList();
            for (var future : executor.invokeAll(calls)) {
                assertSameDelivery(first, future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, count("agent_v2_literature_deliveries"));
        assertEquals(1L, count("agent_turns"));
        assertEquals(2L, count("agent_messages"));
        verify(starts, times(1)).start(eq(7L), any(), any());
        verify(loop, times(1)).execute(eq(7L), any(), any());
        verify(synthesis, times(1)).compose(any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void transactionLevelConcurrentOpenConvergesWithoutOrphanFacts()
            throws Exception {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "workspace", "mock", "model", 4, false));
        int messagesBefore = count(
                "select count(*) from agent_messages where session_id=?",
                session.getId());
        int turnsBefore = count(
                "select count(*) from agent_turns where session_id=?",
                session.getId());
        var executor = Executors.newFixedThreadPool(8);
        List<LiteratureDeliveryEntity> opened;
        try {
            List<Callable<LiteratureDeliveryEntity>> calls =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(index ->
                                    (Callable<LiteratureDeliveryEntity>) () ->
                                            transactions.open(
                                                    7L, session.getId(),
                                                    "database-race", "hash-a",
                                                    "query", 10, null, false,
                                                    "owner", "token",
                                                    Instant.now()
                                                            .plusSeconds(60)))
                            .toList();
            opened = executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, opened.stream()
                .map(LiteratureDeliveryEntity::turnId).distinct().count());
        assertEquals(messagesBefore + 1, count(
                "select count(*) from agent_messages where session_id=?",
                session.getId()));
        assertEquals(turnsBefore + 1, count(
                "select count(*) from agent_turns where session_id=?",
                session.getId()));
        assertThrows(IllegalArgumentException.class, () ->
                transactions.open(
                        7L, session.getId(), "database-race", "hash-b",
                        "different", 10, null, false,
                        "owner", "token", Instant.now().plusSeconds(60)));
        assertEquals(messagesBefore + 1, count(
                "select count(*) from agent_messages where session_id=?",
                session.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void synthesisFailureLeavesNoAssistantMessage() {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "workspace", "mock", "model", 4, false));
        PlanId planId = new PlanId("plan-provider-failure");
        PersistedStepRecoverySucceeded terminal =
                org.mockito.Mockito.mock(PersistedStepRecoverySucceeded.class);
        when(terminal.taskFrame()).thenReturn(org.mockito.Mockito.mock(
                io.paperagent.v2.contracts.TaskFrame.class));
        when(terminal.plan()).thenReturn(org.mockito.Mockito.mock(
                io.paperagent.v2.contracts.Plan.class));
        var versioned = org.mockito.Mockito.mock(
                io.paperagent.v2.persistence.VersionedCheckpoint.class);
        when(versioned.checkpoint()).thenReturn(org.mockito.Mockito.mock(
                io.paperagent.v2.contracts.Checkpoint.class));
        when(terminal.checkpoint()).thenReturn(versioned);
        when(starts.start(eq(7L), any(), any()))
                .thenReturn(new FreshExecutionRecoveryRequired(planId));
        when(loop.execute(eq(7L), any(), any())).thenReturn(
                new PersistentPlanAgentLoopOutcome(
                        planId, 1,
                        PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                        Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty()));
        when(recovery.inspect(planId))
                .thenReturn(PersistenceResult.found(terminal));
        when(synthesis.compose(any())).thenThrow(
                new IllegalStateException("provider rejected"));

        assertThrows(IllegalStateException.class, () -> service.execute(
                7L, session.getId(), new V2LiteratureTurnRequest(
                        "failure query", 10, null, false,
                        "provider-failure")));
        assertEquals(0, count(
                "select count(*) from agent_messages "
                        + "where session_id=? and role='assistant'",
                session.getId()));
        assertEquals(1, count(
                "select count(*) from agent_messages "
                        + "where session_id=? and role='user'",
                session.getId()));
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table, Long.class);
    }

    private static void assertSameDelivery(
            V2LiteratureTurnResponse expected,
            V2LiteratureTurnResponse actual) {
        assertEquals(expected.turnId(), actual.turnId());
        assertEquals(expected.planId(), actual.planId());
        assertEquals(expected.synthesisId(), actual.synthesisId());
        assertEquals(expected.assistantMessageId(),
                actual.assistantMessageId());
        assertEquals(expected.assistantContent(),
                actual.assistantContent());
    }
}
