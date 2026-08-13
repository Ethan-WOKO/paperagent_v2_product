package com.yanban.api.agent.v2.chain.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.api.ProjectChainTurnCoordinator;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import io.paperagent.v2.chain.ChainInstructionRelation;
import org.junit.jupiter.api.Test;

class ProductChainReceivedPlannerProgressionTest {

    @Test
    void delegatesTheExactVerifiedReceivedCommandToTheFormalTurnBoundary() {
        ProjectChainTurnCoordinator turns = mock(ProjectChainTurnCoordinator.class);
        ProductChainReceivedPlannerProgression progression =
                new ProductChainReceivedPlannerProgression(turns);
        ProductChainReceivedCommandSource.ReceivedCommand command = command();
        V2NaturalLanguageTurnResponse expected =
                new V2NaturalLanguageTurnResponse(
                        2L, 3L, 4L, null, "request-1", "PERSISTENT_PLAN_EXECUTE",
                        null, "plan-1", false, "request-1");
        when(turns.resumeReceivedPlanner(command)).thenReturn(expected);

        assertThat(progression.advance(command)).isSameAs(expected);

        verify(turns).resumeReceivedPlanner(command);
    }

    @Test
    void rejectsMissingReceivedCommandBeforeItCanReachTheTurnBoundary() {
        ProjectChainTurnCoordinator turns = mock(ProjectChainTurnCoordinator.class);
        ProductChainReceivedPlannerProgression progression =
                new ProductChainReceivedPlannerProgression(turns);

        assertThatNullPointerException().isThrownBy(() -> progression.advance(null));
    }

    private static ProductChainReceivedCommandSource.ReceivedCommand command() {
        return new ProductChainReceivedCommandSource.ReceivedCommand(
                "command-1", "task-1", "instruction-1", "event-1",
                1L, 2L, "request-1",
                "a".repeat(64), ChainInstructionRelation.INITIAL,
                ChainInstructionRelation.INITIAL, 3L, 4L,
                "request-1", "b".repeat(64), null, null);
    }
}
