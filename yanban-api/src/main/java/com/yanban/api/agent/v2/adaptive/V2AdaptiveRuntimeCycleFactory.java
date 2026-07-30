package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.adaptive.reflection.ReflectionOutcome;
import com.yanban.api.agent.v2.loop.*;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionActivationLeaseAttempt;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class V2AdaptiveRuntimeCycleFactory {
    private final AuthenticatedPersistentPlanAgentLoopComposer loop;
    private final NaturalLanguageStepKernelFactory kernels;
    private final V2ReplanRequestMaterializer replans =
            new V2ReplanRequestMaterializer();

    public V2AdaptiveRuntimeCycleFactory(
            AuthenticatedPersistentPlanAgentLoopComposer loop,
            NaturalLanguageStepKernelFactory kernels) {
        this.loop = loop;
        this.kernels = kernels;
    }

    public V2AdaptiveCyclePort create(
            Map<String, String> bindings, String owner, String token,
            Instant expiresAt, String authoritySuffix, Instant authorityTime) {
        Map<PlanStepId, ToolId> tools = new LinkedHashMap<>();
        bindings.forEach((step, tool) ->
                tools.put(new PlanStepId(step), new ToolId(tool)));
        var kernel = kernels.create(tools);
        return command -> {
            Instant eventTime = authorityTime.plusMillis(
                    10L + command.cycle() * 4L);
            var loopCommand = new PersistentPlanAgentLoopCommand(
                    1,
                    new StepRecoveryLeaseAttempt(owner, token, expiresAt),
                    new StepActivationAttempt(
                            owner, token, expiresAt,
                            new StepActivationEventDraft(
                                    new EventId("adaptive-activate-"
                                            + authoritySuffix + "-"
                                            + command.cycle()),
                                    eventTime,
                                    new EventType("STEP_ACTIVATED"),
                                    Optional.empty(),
                                    "adaptive-" + authoritySuffix,
                                    new InlineEventPayload(
                                            new ObjectValue(Map.of()))),
                            eventTime.plusMillis(1)),
                    new EffectDrivenStepProgressionActivationLeaseAttempt(
                            owner, token, expiresAt),
                    Optional.empty());
            ReflectionOutcome pending =
                    command.replanRequest() instanceof ReflectionOutcome value
                            ? value : null;
            PersistentPlanAgentLoopOutcome result =
                    loop.executeWithKernelAndReplanFactory(
                            command.userId(), command.turnId(), loopCommand,
                            kernel, active -> pending == null
                                    ? null : replans.materialize(
                                            active, pending));
            boolean succeeded =
                    result.state() == PersistentPlanAgentLoopState.PLAN_SUCCEEDED;
            V2AdaptiveCyclePort.CycleResult.State state;
            if (succeeded) {
                state = V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED;
            } else if (result.state()
                    == PersistentPlanAgentLoopState.REPLAN_REQUIRED
                    || result.state()
                    == PersistentPlanAgentLoopState.REPLAN_APPLIED
                    || result.state()
                    == PersistentPlanAgentLoopState.REPLAN_REPLAYED) {
                state = V2AdaptiveCyclePort.CycleResult.State.REPLAN_REQUIRED;
            } else {
                state = V2AdaptiveCyclePort.CycleResult.State.FAILED;
            }
            return new V2AdaptiveCyclePort.CycleResult(
                    state,
                    result.stepId().map(PlanStepId::value).orElse(null),
                    result.state().name(), succeeded,
                    result.replan().orElse(null));
        };
    }
}
