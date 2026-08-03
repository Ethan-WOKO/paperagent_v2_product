package com.yanban.api.agent.v2.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.adaptive.V2AdaptiveCyclePort;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveRuntimeCycleFactory;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V2AdaptiveRuntimeCycleContextRecoveryTest {
    @Test
    void stepContextFailureBecomesRecoveryPendingInsteadOfTerminalFailure() {
        AuthenticatedPersistentPlanAgentLoopComposer loop =
                mock(AuthenticatedPersistentPlanAgentLoopComposer.class);
        NaturalLanguageStepKernelFactory kernels =
                mock(NaturalLanguageStepKernelFactory.class);
        ModelProvider provider = mock(ModelProvider.class);
        SingleTurnStepKernel kernel = mock(SingleTurnStepKernel.class);
        AutonomousNaturalLanguageStepTurnAdapter turn =
                mock(AutonomousNaturalLanguageStepTurnAdapter.class);
        when(kernels.createAutonomous(
                eq(provider), eq(false), eq(1L), eq(3L)))
                .thenReturn(new NaturalLanguageStepKernelFactory.AutonomousKernel(
                        kernel, turn));
        when(loop.executeAutonomousEffect(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new PersistentPlanAgentLoopException(
                        "kernel", new StepModelCallGuardException(
                                "STEP_CONTEXT_NOT_READY")));
        V2AdaptiveCyclePort cycles = new V2AdaptiveRuntimeCycleFactory(
                loop, kernels).create(
                        Map.of(), "owner", "token",
                        Instant.parse("2030-01-01T00:00:00Z"),
                        "authority", Instant.EPOCH, provider);

        V2AdaptiveCyclePort.CycleResult result = cycles.executeOne(
                new V2AdaptiveCyclePort.CycleCommand(
                        1L, 3L, "plan-1", 1, null));

        assertThat(result.state()).isEqualTo(
                V2AdaptiveCyclePort.CycleResult.State.RECOVERY_PENDING);
        assertThat(result.detail()).isEqualTo(
                "STEP_CONTEXT_RECOVERY_PENDING");
    }
}
