package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartComposer;
import com.yanban.api.agent.v2.loop.AuthenticatedPersistentPlanAgentLoopComposer;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.synthesis.DefaultFinalSynthesisComposer;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class V2LiteratureTurnServiceTest {
    @Test
    void rejectsProjectSessionBeforeCreatingAnyTurnOrPlan() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        LiteratureDeliveryTransactions deliveries =
                mock(LiteratureDeliveryTransactions.class);
        var starts = mock(
                AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        var loop = mock(AuthenticatedPersistentPlanAgentLoopComposer.class);
        var recovery = mock(StepRecoveryRepository.class);
        var synthesis = mock(DefaultFinalSynthesisComposer.class);
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(
                new AgentSession(
                        7L, "project", "mock", "model", 4, false,
                        AgentSessionScope.PROJECT, 91L)));
        var service = new V2LiteratureTurnService(
                sessions, deliveries, starts, loop, recovery, synthesis);

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 9L, new V2LiteratureTurnRequest(
                        "query", 10, null, false, "request-1")));

        verifyNoInteractions(deliveries, starts, loop, recovery, synthesis);
    }

    @Test
    void rejectsMissingOwnerQualifiedSessionBeforeCreatingFacts() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        LiteratureDeliveryTransactions deliveries =
                mock(LiteratureDeliveryTransactions.class);
        var service = new V2LiteratureTurnService(
                sessions, deliveries,
                mock(AuthenticatedAgentTurnFreshExecutionStartComposer.class),
                mock(AuthenticatedPersistentPlanAgentLoopComposer.class),
                mock(StepRecoveryRepository.class),
                mock(DefaultFinalSynthesisComposer.class));
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 9L, new V2LiteratureTurnRequest(
                        "query", 10, null, false, "request-1")));
        verifyNoInteractions(deliveries);
    }
}
