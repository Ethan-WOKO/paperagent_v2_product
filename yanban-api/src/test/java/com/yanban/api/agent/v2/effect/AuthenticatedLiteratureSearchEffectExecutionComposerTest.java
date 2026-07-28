package com.yanban.api.agent.v2.effect;

import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.compatibility.literature.LiteratureSearchRequestAuthority;
import com.yanban.core.tool.ToolExecutionContext;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Optional;

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

    @Test
    void unsupportedToolFailsBeforeClaimAndExecutor() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        var unsupported = fixture.intent(
                "paper.polish", LiteratureSearchEffectTestFixtures.STEP,
                LiteratureSearchEffectTestFixtures.ACTIVATION,
                fixture.lease.ownerId(), fixture.lease.fencingToken(),
                Map.of("query", new TextValue("topic")));
        when(fixture.intents.find(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(unsupported));

        var failure = assertThrows(
                AuthenticatedLiteratureSearchEffectExecutionException.class,
                () -> fixture.composer.execute(7L, 42L, fixture.command()));
        assertEquals("intent.authority", failure.path());
        verifyNoInteractions(fixture.claims);
        verifyNoInteractions(fixture.executor);
    }

    @Test
    void wrongStepFailsBeforeClaimAndExecutor() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        rejectChangedAuthority(fixture, fixture.intent(
                AuthenticatedLiteratureSearchEffectExecutionComposer.V2_TOOL,
                new PlanStepId("step-other"),
                LiteratureSearchEffectTestFixtures.ACTIVATION,
                fixture.lease.ownerId(), fixture.lease.fencingToken(),
                Map.of("query", new TextValue("topic"))));
    }

    @Test
    void wrongActivationFailsBeforeClaimAndExecutor() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        rejectChangedAuthority(fixture, fixture.intent(
                AuthenticatedLiteratureSearchEffectExecutionComposer.V2_TOOL,
                LiteratureSearchEffectTestFixtures.STEP,
                new EventId("activation-other"),
                fixture.lease.ownerId(), fixture.lease.fencingToken(),
                Map.of("query", new TextValue("topic"))));
    }

    @Test
    void wrongLeaseOwnerOrFenceFailsBeforeClaimAndExecutor() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        rejectChangedAuthority(fixture, fixture.intent(
                AuthenticatedLiteratureSearchEffectExecutionComposer.V2_TOOL,
                LiteratureSearchEffectTestFixtures.STEP,
                LiteratureSearchEffectTestFixtures.ACTIVATION,
                "other-owner", fixture.lease.fencingToken() + 1,
                Map.of("query", new TextValue("topic"))));
    }

    @Test
    void deliveryAuthorityMismatchFailsBeforeClaimAndExecutor() {
        List<LiteratureSearchRequestAuthority> mismatches = List.of(
                new LiteratureSearchRequestAuthority(
                        "other query", 10, null, false),
                new LiteratureSearchRequestAuthority(
                        "graph retrieval", 11, null, false),
                new LiteratureSearchRequestAuthority(
                        "graph retrieval", 10, 2025, false),
                new LiteratureSearchRequestAuthority(
                        "graph retrieval", 10, null, true));
        for (LiteratureSearchRequestAuthority mismatch : mismatches) {
            var fixture = new LiteratureSearchEffectTestFixtures();
            when(fixture.authorities.find(7L, 42L))
                    .thenReturn(Optional.of(mismatch));
            var failure = assertThrows(
                    AuthenticatedLiteratureSearchEffectExecutionException.class,
                    () -> fixture.composer.execute(
                            7L, 42L, fixture.command()));
            assertEquals("request.authority", failure.path());
            verifyNoInteractions(fixture.claims);
            verifyNoInteractions(fixture.executor);
        }
    }

    private static void rejectChangedAuthority(
            LiteratureSearchEffectTestFixtures fixture,
            io.paperagent.v2.persistence.PersistedEffectIntent changed) {
        when(fixture.intents.find(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(changed));
        var failure = assertThrows(
                AuthenticatedLiteratureSearchEffectExecutionException.class,
                () -> fixture.composer.execute(7L, 42L, fixture.command()));
        assertEquals("intent.authority", failure.path());
        verifyNoInteractions(fixture.claims);
        verifyNoInteractions(fixture.executor);
    }
}
