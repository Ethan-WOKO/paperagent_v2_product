package com.yanban.api.agent.v2.progression;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.activation.composition.ReadyStepActivationCompositionRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.progression.StepProgressionInspector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnStepProgressionComposerTest {
    @Test
    void resolvesAuthenticatedPlanAndDelegatesExactReadyAuthority() {
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        ProductPlanIdDerivation planIds =
                mock(ProductPlanIdDerivation.class);
        StepProgressionInspector inspector =
                mock(StepProgressionInspector.class);
        StepActivationComposer activation =
                mock(StepActivationComposer.class);
        ActiveStepCompletionComposer completion =
                mock(ActiveStepCompletionComposer.class);
        var composer = new AuthenticatedAgentTurnStepProgressionComposer(
                contexts, planIds, inspector, activation, completion);
        var context = new VerifiedAgentTurnProductContext(
                new AgentRunIdentity(
                        "AGENT_TURN", "42", 7L, 9L, null),
                Optional.empty());
        PlanId planId = new PlanId("product-plan");
        PersistedStepRecoveryReady ready =
                mock(PersistedStepRecoveryReady.class);
        StepActivationAttempt attempt = mock(StepActivationAttempt.class);
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(planIds.derive(context.identity())).thenReturn(planId);
        when(ready.planId()).thenReturn(planId);
        when(inspector.inspect(planId))
                .thenReturn(PersistenceResult.found(ready));
        assertNull(composer.activateReady(7L, 42L, attempt));

        ArgumentCaptor<ReadyStepActivationCompositionRequest> captured =
                ArgumentCaptor.forClass(
                        ReadyStepActivationCompositionRequest.class);
        verify(activation).composeReady(captured.capture());
        assertSame(ready, captured.getValue().ready());
        assertSame(attempt, captured.getValue().attempt());
    }
}
