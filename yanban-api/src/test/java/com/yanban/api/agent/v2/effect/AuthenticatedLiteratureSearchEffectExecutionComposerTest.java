package com.yanban.api.agent.v2.effect;

import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.core.tool.ToolExecutionContext;
import io.paperagent.v2.contracts.PlanId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedLiteratureSearchEffectExecutionComposerTest {
    @Test
    void ownershipIsResolvedBeforeCommandAndRecovery() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        var failure = new AgentTurnProductContextResolutionException(
                AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                "turnId");
        when(fixture.contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> fixture.composer.execute(
                        7L, 404L, fixture.command())));
        verifyNoInteractions(fixture.recoverer);
        assertNull(ToolExecutionContext.getCurrentUserId());
    }

    @Test
    void callerPlanMustMatchServerDerivedPlan() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        var changed = new AuthenticatedLiteratureSearchEffectExecutionCommand(
                new PlanId("other-plan"),
                fixture.command().toolCallId(),
                fixture.command().recoveryAttempt());

        var failure = assertThrows(
                AuthenticatedLiteratureSearchEffectExecutionException.class,
                () -> fixture.composer.execute(7L, 42L, changed));
        assertEquals("command.planId", failure.path());
        verify(fixture.recoverer, never()).recover(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nullCommandFailsClosedAndClearsThreadContext() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        ToolExecutionContext.setCurrentUserId(999L);
        assertThrows(
                AuthenticatedLiteratureSearchEffectExecutionException.class,
                () -> fixture.composer.execute(7L, 42L, null));
        assertNull(ToolExecutionContext.getCurrentUserId());
        verifyNoInteractions(fixture.recoverer);
    }
}
