package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @MockBean
    AuthenticatedAgentTurnFreshExecutionStartComposer starts;
    @MockBean
    AuthenticatedPersistentPlanAgentLoopComposer loop;
    @MockBean
    StepRecoveryRepository recovery;
    @MockBean
    DefaultFinalSynthesisComposer synthesis;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void exactReplayAndEightConcurrentCallsDeliverOneMessageSet() throws Exception {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "workspace", "mock", "model", 4, false));
        PlanId planId = new PlanId("plan-vertical");
        PersistedStepRecoverySucceeded terminal =
                org.mockito.Mockito.mock(PersistedStepRecoverySucceeded.class);
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
                        finalValue, PersistenceOutcome.APPLIED));
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
