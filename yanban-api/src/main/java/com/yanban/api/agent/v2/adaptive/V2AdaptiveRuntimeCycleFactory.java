package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionOutcome;
import com.yanban.api.agent.v2.context.V2ExecutionContextSource;
import com.yanban.api.agent.v2.loop.*;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionActivationLeaseAttempt;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.providers.ModelProvider;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class V2AdaptiveRuntimeCycleFactory {
    private static final Logger log = LoggerFactory.getLogger(
            V2AdaptiveRuntimeCycleFactory.class);
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
        return create(bindings, owner, token, expiresAt, authoritySuffix,
                authorityTime, null);
    }

    public V2AdaptiveCyclePort create(
            Map<String, String> bindings, String owner, String token,
            Instant expiresAt, String authoritySuffix, Instant authorityTime,
            ModelProvider requestProvider) {
        Map<PlanStepId, ToolId> tools = new LinkedHashMap<>();
        bindings.forEach((step, tool) ->
                tools.put(new PlanStepId(step), new ToolId(tool)));
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
            PersistentPlanAgentLoopOutcome result;
            List<String> diagnostics = List.of();
            try {
                if (command.action()
                        == V2AdaptiveCyclePort.Action.COMPLETE_STEP) {
                    result = loop.completeAutonomousStep(
                            command.userId(), command.turnId(),
                            loopCommand);
                } else {
                    NaturalLanguageStepKernelFactory.AutonomousKernel
                            autonomous = requestProvider == null
                            ? kernels.createAutonomous(
                                    pending != null,
                                    command.modelContextFacts())
                            : kernels.createAutonomous(
                                    requestProvider, pending != null,
                                    command.modelContextFacts());
                    result = loop.executeAutonomousEffect(
                            command.userId(), command.turnId(), loopCommand,
                            autonomous.kernel(),
                            active -> pending == null
                                    ? null : replans.materialize(
                                            active, pending),
                            requestProvider);
                    diagnostics = autonomous.diagnostics();
                }
            } catch (PersistentPlanAgentLoopException failure) {
                logCycleFailure(
                        command, failure.diagnosticStage(), failure);
                throw new CycleStageException(
                        agentLoopStage(failure.diagnosticStage()));
            } catch (RuntimeException failure) {
                logCycleFailure(command, "agentLoop", failure);
                throw new CycleStageException("AGENT_LOOP");
            }
            boolean succeeded =
                    result.state() == PersistentPlanAgentLoopState.PLAN_SUCCEEDED;
            V2AdaptiveCyclePort.CycleResult.State state;
            if (succeeded) {
                state = V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED;
            } else if (result.state()
                    == PersistentPlanAgentLoopState
                            .EFFECT_SUCCEEDED_AWAITING_REFLECTION) {
                state = V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED;
            } else if (result.state()
                    == PersistentPlanAgentLoopState
                            .STEP_RESULT_PROPOSED_AWAITING_REFLECTION) {
                state = V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED;
            } else if (result.state()
                    == PersistentPlanAgentLoopState
                            .EFFECT_RECOVERY_PENDING) {
                state = V2AdaptiveCyclePort.CycleResult.State
                        .RECOVERY_PENDING;
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
            List<String> facts = new ArrayList<>(
                    authoritativeFacts(result));
            facts.addAll(diagnostics);
            String detail = diagnostics.isEmpty()
                    ? result.state().name()
                    : diagnostics.get(diagnostics.size() - 1);
            return new V2AdaptiveCyclePort.CycleResult(
                    state,
                    result.stepId().map(PlanStepId::value).orElse(null),
                    detail, succeeded,
                    result.replan().orElse(null),
                    facts,
                    result.receiptFacts().isPresent(),
                    result.receiptFacts()
                            .map(value -> !"SUCCESS".equals(value.status()))
                            .orElse(false),
                    result.receiptFacts()
                            .map(value -> List.of(
                                    new ReceiptId(value.receiptId())))
                            .orElse(List.of()),
                    result.stepResult());
        };
    }

    private static void logCycleFailure(
            V2AdaptiveCyclePort.CycleCommand command,
            String stage, RuntimeException failure) {
        log.warn(
                "V2 adaptive cycle failed stage={} planId={} turnId={} "
                        + "cycle={} action={} exceptionType={} causeType={} "
                        + "origin={}",
                stage,
                command.planId(),
                command.turnId(),
                command.cycle(),
                command.action(),
                V2SafeFailureDiagnostics.exceptionType(failure),
                V2SafeFailureDiagnostics.causeType(failure),
                V2SafeFailureDiagnostics.origin(failure));
    }

    static Map<PlanStepId, ToolId> bindingsForCycle(
            Map<PlanStepId, ToolId> base, Object replanRequest) {
        Map<PlanStepId, ToolId> current = new LinkedHashMap<>(base);
        if (replanRequest instanceof ReflectionOutcome pendingBindings) {
            pendingBindings.replacementSteps().forEach(value -> {
                if (value.internalToolId() != null) {
                    current.put(
                            value.step().id(), value.internalToolId());
                }
            });
        }
        return current;
    }

    V2ExecutionContextSource contextSource() {
        return kernels.contextSource();
    }

    private static List<String> authoritativeFacts(
            PersistentPlanAgentLoopOutcome result) {
        List<String> facts = new ArrayList<>();
        facts.add("loopState=" + result.state().name());
        result.cut().ifPresent(value ->
                facts.add("completionCut=" + value));
        result.replan().ifPresent(value ->
                facts.add("persistedReplan=" + value));
        result.receiptFacts().ifPresent(value -> {
            facts.add("executionReceipt=" + value);
            facts.add("executionReceiptId=" + value.receiptId());
        });
        result.stepResult().ifPresent(value -> {
            facts.add("stepResultId=" + value.resultId());
            facts.add("stepResultStatus=" + value.status().name());
            facts.add("stepResultSource=" + value.source().name());
            facts.add("stepResultEvidenceCount="
                    + value.evidenceReceiptIds().size());
        });
        return List.copyOf(facts);
    }

    static String agentLoopStage(String diagnosticStage) {
        if (diagnosticStage == null || diagnosticStage.isBlank()) {
            return "AGENT_LOOP";
        }
        String normalized = diagnosticStage.toUpperCase(Locale.ROOT)
                .replace('.', '_');
        if (!normalized.matches("[A-Z0-9_]+")) {
            return "AGENT_LOOP";
        }
        if (normalized.endsWith("_EXCEPTION")) {
            normalized = normalized.substring(
                    0, normalized.length() - "_EXCEPTION".length());
        }
        String stage = "LOOP_" + normalized;
        if (stage.length() <= 48) {
            return stage;
        }
        return stage.substring(0, 39)
                + "_" + String.format(
                        Locale.ROOT, "%08X", diagnosticStage.hashCode());
    }

    static final class CycleStageException extends RuntimeException {
        private final String stage;

        CycleStageException(String stage) {
            super("adaptive cycle stage failed");
            this.stage = stage;
        }

        String stage() {
            return stage;
        }
    }
}
