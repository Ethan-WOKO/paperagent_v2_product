package com.yanban.api.agent.v2.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersistentPlanAgentLoopExceptionTest {
    @Test
    void preservesStepContextClassificationWithoutInspectingMessages() {
        PersistentPlanAgentLoopException failure =
                new PersistentPlanAgentLoopException(
                        "kernel", new StepModelCallGuardException(
                                "STEP_CONTEXT_NOT_READY"));

        assertThat(failure.stepContextGuardFailure()).isTrue();
        assertThat(failure.getCause())
                .isInstanceOf(StepModelCallGuardException.class);
    }
}
