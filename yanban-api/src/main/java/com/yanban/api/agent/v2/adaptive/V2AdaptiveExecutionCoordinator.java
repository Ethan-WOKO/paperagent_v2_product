package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.adaptive.reflection.*;
import com.yanban.api.agent.v2.result.V2StepResultService;
import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import com.yanban.api.agent.v2.result.V2StepResultSource;
import com.yanban.api.agent.v2.result.V2StepResultStatus;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded policy around exactly one durable Runtime cycle per reflection. */
public final class V2AdaptiveExecutionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(
            V2AdaptiveExecutionCoordinator.class);
    static final int MAX_REPLANS = 3;
    private static final int MAX_PLAN_STEPS = 8;
    // One initial plan plus every bounded replacement plan and its apply cycle.
    static final int MAX_CYCLES = MAX_PLAN_STEPS
            + MAX_REPLANS * (1 + MAX_PLAN_STEPS);
    private static final int MAX_TOTAL_ITERATIONS = MAX_CYCLES * 2;
    private static final String NO_PROGRESS_GUARD =
            "noProgressGuard=the model has already persisted a Step-result "
                    + "proposal for this active Step; choose COMPLETE if the "
                    + "proposal satisfies it, REPLAN if a changed approach "
                    + "is required, or "
                    + "FAIL if execution cannot proceed";
    private final V2AdaptiveCyclePort cycles;
    private final ReflectionProvider reflections;
    private final StrictReflectionDecisionParser parser;
    private final V2StepResultService stepResults;

    public V2AdaptiveExecutionCoordinator(
            V2AdaptiveCyclePort cycles, ReflectionProvider reflections,
            StrictReflectionDecisionParser parser) {
        this(cycles, reflections, parser, null);
    }

    public V2AdaptiveExecutionCoordinator(
            V2AdaptiveCyclePort cycles, ReflectionProvider reflections,
            StrictReflectionDecisionParser parser,
            V2StepResultService stepResults) {
        this.cycles = cycles;
        this.reflections = reflections;
        this.parser = parser;
        this.stepResults = stepResults;
    }

    public V2AdaptiveExecutionResult execute(Command command) {
        List<V2AdaptiveTurnResponse.Step> timeline =
                new ArrayList<>(command.steps());
        Map<String, Integer> stepIndexes =
                new LinkedHashMap<>(command.stepIndexes());
        reconcileAcceptedCompleted(
                timeline, stepIndexes, command.planId());
        Set<String> receiptBackedSteps = new LinkedHashSet<>();
        Map<String, LinkedHashSet<ReceiptId>> receiptIdsByStep =
                new LinkedHashMap<>();
        List<String> accumulatedExecutionFacts = new ArrayList<>(
                command.baseContext().recentExecutionFacts());
        Object pendingReplan = null;
        ReflectionOutcome pendingDecision = null;
        boolean pendingRepair = false;
        V2AdaptiveCyclePort.Action nextAction =
                V2AdaptiveCyclePort.Action.EXECUTE;
        String acceptedFinalText = null;
        int replanCount = 0;
        int repairCount = 0;
        int executionCycles = 0;
        int totalIterations = 0;
        while (totalIterations < MAX_TOTAL_ITERATIONS) {
            boolean completionTransition =
                    nextAction == V2AdaptiveCyclePort.Action.COMPLETE_STEP;
            if (!completionTransition
                    && executionCycles >= MAX_CYCLES) {
                return failed(timeline, "CYCLE_LIMIT_EXCEEDED",
                        executionCycles, replanCount, repairCount);
            }
            totalIterations++;
            if (!completionTransition) {
                executionCycles++;
            }
            V2AdaptiveCyclePort.CycleResult cycle;
            try {
                cycle = cycles.executeOne(
                        new V2AdaptiveCyclePort.CycleCommand(
                                command.userId(), command.turnId(),
                                command.planId(), totalIterations,
                                pendingReplan,
                                nextAction));
            } catch (V2AdaptiveRuntimeCycleFactory.CycleStageException
                    failure) {
                return failed(timeline,
                        "CYCLE_" + failure.stage() + "_EXCEPTION",
                        executionCycles, replanCount, repairCount);
            } catch (RuntimeException failure) {
                logFailure("cycle.execute", command.planId(),
                        totalIterations, failure);
                return failed(timeline, "CYCLE_EXECUTION_EXCEPTION",
                        executionCycles, replanCount, repairCount);
            }
            pendingReplan = null;
            V2AdaptiveCyclePort.Action completedAction = nextAction;
            nextAction = V2AdaptiveCyclePort.Action.EXECUTE;
            updateExisting(timeline, stepIndexes, cycle, completedAction);
            appendExecutionFacts(accumulatedExecutionFacts, cycle);
            if (cycle.receiptBacked() && cycle.stepId() != null) {
                receiptBackedSteps.add(cycle.stepId());
                receiptIdsByStep.computeIfAbsent(
                                cycle.stepId(), ignored ->
                                        new LinkedHashSet<>())
                        .addAll(cycle.receiptIds());
            }
            boolean replanApplied = cycle.replanAuthority() != null
                    && pendingDecision != null;
            if (replanApplied) {
                applyPersistedReplan(
                        timeline, stepIndexes, cycle, pendingDecision);
                if (pendingRepair) {
                    repairCount++;
                }
                pendingRepair = false;
                pendingDecision = null;
            }
            if (replanApplied) {
                continue;
            }

            if (completedAction
                    == V2AdaptiveCyclePort.Action.COMPLETE_STEP) {
                if (cycle.state()
                        == V2AdaptiveCyclePort.CycleResult.State
                                .PLAN_SUCCEEDED
                        && cycle.durableSucceeded()) {
                    return new V2AdaptiveExecutionResult(
                            "SUCCEEDED", timeline, acceptedFinalText, null,
                            executionCycles, replanCount, repairCount);
                }
                if (cycle.state()
                        == V2AdaptiveCyclePort.CycleResult.State.FAILED) {
                    return failed(timeline, "STEP_COMPLETION_FAILED",
                            executionCycles, replanCount, repairCount);
                }
                acceptedFinalText = null;
                continue;
            }
            if (cycle.state()
                    == V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED
                    && cycle.stepResult()
                            .map(value -> value.status()
                                    == V2StepResultStatus.ACCEPTED)
                            .orElse(false)) {
                V2StepResultSnapshot accepted = cycle.stepResult()
                        .orElseThrow();
                acceptedFinalText = accepted.acceptedText()
                        .orElseThrow();
                recordAcceptedStepResult(
                        timeline, stepIndexes, cycle.stepId(),
                        acceptedFinalText);
                nextAction = V2AdaptiveCyclePort.Action.COMPLETE_STEP;
                continue;
            }
            if (cycle.state()
                    == V2AdaptiveCyclePort.CycleResult.State
                            .RECOVERY_PENDING) {
                return new V2AdaptiveExecutionResult(
                        "RUNNING", timeline, null, null,
                        executionCycles, replanCount, repairCount);
            }
            Optional<String> recoveredTerminalText =
                    terminalAcceptedText(command.planId(), cycle);
            if (recoveredTerminalText.isPresent()) {
                markTerminalSuccess(timeline);
                return new V2AdaptiveExecutionResult(
                        "SUCCEEDED", timeline,
                        recoveredTerminalText.orElseThrow(), null,
                        executionCycles, replanCount, repairCount);
            }

            ReflectionOutcome decision;
            ReflectionContext reflectionContext =
                    command.reflectionContext(
                            accumulatedExecutionFacts, timeline,
                            cycle.stepId(),
                            cycle.stepResult().map(
                                    V2AdaptiveExecutionCoordinator
                                            ::reflectionStepResult)
                                    .orElse(null),
                            completedResultFacts(command.planId()),
                            currentReceiptIds(
                                    receiptIdsByStep, cycle.stepId()));
            ReflectionResolution reflection = reflect(
                    reflectionContext, command.planId());
            if (reflection.failureCode() != null) {
                return failed(timeline, reflection.failureCode(),
                        executionCycles, replanCount, repairCount);
            }
            decision = reflection.decision();
            if (requiresNoProgressReflection(cycle, decision)) {
                ReflectionResolution reconsidered = reflect(
                        withNoProgressGuard(reflectionContext),
                        command.planId());
                if (reconsidered.failureCode() != null) {
                    return failed(timeline, reconsidered.failureCode(),
                            executionCycles, replanCount, repairCount);
                }
                decision = reconsidered.decision();
                if (decision.decision() == ReflectionAction.CONTINUE) {
                    rejectStepResult(cycle, decision.reason());
                    return failed(timeline, "REFLECTION_NO_PROGRESS",
                            executionCycles, replanCount, repairCount);
                }
            }
            if (decision.decision() == ReflectionAction.COMPLETE) {
                if (cycle.state()
                        == V2AdaptiveCyclePort.CycleResult.State
                                .PLAN_SUCCEEDED
                        && cycle.durableSucceeded()) {
                    return new V2AdaptiveExecutionResult(
                            "SUCCEEDED", timeline, decision.finalText(), null,
                            executionCycles, replanCount, repairCount);
                }
                if (cycle.state()
                        != V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED
                        || cycle.stepId() == null
                        || (!receiptBackedSteps.contains(cycle.stepId())
                        && cycle.stepResult().isEmpty())) {
                    return failed(timeline,
                            "PREMATURE_COMPLETE", executionCycles, replanCount,
                            repairCount);
                }
                String acceptedStepText;
                try {
                    acceptedStepText = acceptStepResult(
                            command.planId(), cycle, decision,
                            currentReceiptIds(
                                    receiptIdsByStep, cycle.stepId()));
                } catch (RuntimeException failure) {
                    logFailure("stepResult.accept", command.planId(),
                            totalIterations, failure);
                    return failed(timeline,
                            "STEP_RESULT_PERSISTENCE_FAILED",
                            executionCycles, replanCount, repairCount);
                }
                recordAcceptedStepResult(
                        timeline, stepIndexes,
                        cycle.stepId(), acceptedStepText);
                acceptedFinalText = acceptedStepText;
                nextAction = V2AdaptiveCyclePort.Action.COMPLETE_STEP;
                continue;
            }
            if (decision.decision() == ReflectionAction.FAIL) {
                rejectStepResult(cycle, decision.reason());
                return failed(timeline, "REFLECTION_FAILED",
                        executionCycles, replanCount, repairCount);
            }
            if (cycle.durableSucceeded()) {
                return failed(timeline, "TERMINAL_DECISION_INVALID",
                        executionCycles, replanCount, repairCount);
            }
            if (decision.decision() == ReflectionAction.CONTINUE) {
                rejectStepResult(cycle, decision.reason());
                continue;
            }
            if (decision.decision() == ReflectionAction.REPLAN) {
                rejectStepResult(cycle, decision.reason());
                if (++replanCount > MAX_REPLANS) {
                    return failed(timeline, "REPLAN_LIMIT_EXCEEDED",
                            executionCycles, replanCount, repairCount);
                }
                pendingReplan = decision;
                pendingDecision = decision;
                pendingRepair = cycle.failedReceipt();
            }
        }
        return failed(timeline, "CYCLE_LIMIT_EXCEEDED",
                executionCycles, replanCount, repairCount);
    }

    private static void updateExisting(
            List<V2AdaptiveTurnResponse.Step> timeline,
            Map<String, Integer> indexes,
            V2AdaptiveCyclePort.CycleResult cycle,
            V2AdaptiveCyclePort.Action completedAction) {
        if (cycle.stepId() == null) {
            return;
        }
        Integer row = indexes.get(cycle.stepId());
        if (row == null || row < 0 || row >= timeline.size()) {
            return;
        }
        V2AdaptiveTurnResponse.Step existing = timeline.get(row);
        String status;
        if (cycle.state()
                == V2AdaptiveCyclePort.CycleResult.State.FAILED
                || cycle.failedReceipt()) {
            status = "FAILED";
        } else if (cycle.state()
                == V2AdaptiveCyclePort.CycleResult.State
                        .RECOVERY_PENDING) {
            status = "RUNNING";
        } else if (cycle.state()
                == V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED
                || completedAction
                        == V2AdaptiveCyclePort.Action.COMPLETE_STEP) {
            status = "SUCCEEDED";
        } else {
            status = "RUNNING";
        }
        String detail = completedAction
                        == V2AdaptiveCyclePort.Action.COMPLETE_STEP
                && existing.detail() != null
                && !existing.detail().isBlank()
                ? existing.detail() : bounded(cycle.detail());
        timeline.set(row, new V2AdaptiveTurnResponse.Step(
                existing.index(), existing.title(), status,
                detail));
    }

    private static void appendExecutionFacts(
            List<String> accumulated,
            V2AdaptiveCyclePort.CycleResult cycle) {
        if (cycle.authoritativeFacts().isEmpty()) {
            accumulated.add(
                    cycle.state() + ": " + bounded(cycle.detail()));
        } else {
            accumulated.addAll(cycle.authoritativeFacts());
        }
    }

    private static void applyPersistedReplan(
            List<V2AdaptiveTurnResponse.Step> timeline,
            Map<String, Integer> indexes,
            V2AdaptiveCyclePort.CycleResult cycle,
            ReflectionOutcome decision) {
        Integer obsoleteIndex = indexes.get(cycle.stepId());
        if (obsoleteIndex != null) {
            V2AdaptiveTurnResponse.Step obsolete =
                    timeline.get(obsoleteIndex);
            timeline.set(obsoleteIndex, new V2AdaptiveTurnResponse.Step(
                    obsolete.index(), obsolete.title(),
                    "SUPERSEDED_BY_REPLAN", decision.reason()));
        }
        for (ReflectionReplacementStep replacement
                : decision.replacementSteps()) {
            String id = replacement.step().id().value();
            if (indexes.containsKey(id)) {
                continue;
            }
            int row = timeline.size();
            indexes.put(id, row);
            timeline.add(new V2AdaptiveTurnResponse.Step(
                    row + 1, replacement.step().intent(),
                    "PENDING", "由重新规划追加"));
        }
    }

    private static V2AdaptiveExecutionResult failed(
            List<V2AdaptiveTurnResponse.Step> steps, String code,
            int reflections, int replans, int repairs) {
        markFailure(steps, code);
        return new V2AdaptiveExecutionResult(
                "FAILED", steps, null, code,
                reflections, replans, repairs);
    }

    private ReflectionResolution reflect(
            ReflectionContext initial, String planId) {
        ReflectionContext context = initial;
        String failureCode = "REFLECTION_PROVIDER_EXCEPTION";
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw;
            try {
                raw = reflections.reflect(context);
            } catch (ReflectionAuditFormatException auditFormatFailure) {
                logFailure("reflection.audit-format", planId,
                        attempt, auditFormatFailure);
                return new ReflectionResolution(
                        null, "REFLECTION_AUDIT_FORMAT_INVALID");
            } catch (RuntimeException providerFailure) {
                logFailure("reflection.provider", planId,
                        attempt, providerFailure);
                failureCode = "REFLECTION_PROVIDER_EXCEPTION";
                context = withReflectionDiagnostic(context, failureCode);
                continue;
            }
            try {
                return new ReflectionResolution(
                        parser.parse(raw), null);
            } catch (ReflectionParseException invalid) {
                logFailure("reflection.parse", planId,
                        attempt, invalid);
                failureCode = "REFLECTION_PARSE_INVALID";
                context = withReflectionDiagnostic(context, failureCode);
            } catch (RuntimeException parserFailure) {
                logFailure("reflection.parser", planId,
                        attempt, parserFailure);
                failureCode = "REFLECTION_PARSER_EXCEPTION";
                context = withReflectionDiagnostic(context, failureCode);
            }
        }
        return new ReflectionResolution(null, failureCode);
    }

    private static void logFailure(
            String stage, String planId, int attempt,
            RuntimeException failure) {
        log.warn(
                "V2 adaptive decision failed stage={} planId={} "
                        + "attempt={} exceptionType={} causeType={} origin={}",
                stage, planId, attempt,
                V2SafeFailureDiagnostics.exceptionType(failure),
                V2SafeFailureDiagnostics.causeType(failure),
                V2SafeFailureDiagnostics.origin(failure));
    }

    private static boolean requiresNoProgressReflection(
            V2AdaptiveCyclePort.CycleResult cycle,
            ReflectionOutcome decision) {
        return decision.decision() == ReflectionAction.CONTINUE
                && cycle.state()
                        == V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED
                && cycle.stepResult()
                        .map(value -> value.status()
                                == V2StepResultStatus.REJECTED)
                        .orElse(false);
    }

    private String acceptStepResult(
            String planId,
            V2AdaptiveCyclePort.CycleResult cycle,
            ReflectionOutcome decision,
            List<ReceiptId> currentReceiptIds) {
        if (stepResults == null) {
            return decision.finalText();
        }
        String acceptedText = cycle.stepResult()
                .map(V2StepResultSnapshot::proposedText)
                .orElse(decision.finalText());
        V2StepResultSnapshot proposal = cycle.stepResult()
                .filter(value -> value.status()
                        != V2StepResultStatus.REJECTED)
                .orElseGet(() -> stepResults.proposeCurrent(
                        new PlanId(planId),
                        new PlanStepId(cycle.stepId()),
                        V2StepResultSource.REFLECTION,
                        acceptedText, currentReceiptIds));
        return stepResults.accept(
                        proposal.resultId(), acceptedText)
                .acceptedText().orElseThrow();
    }

    private void rejectStepResult(
            V2AdaptiveCyclePort.CycleResult cycle, String reason) {
        if (stepResults == null || cycle.stepResult().isEmpty()) {
            return;
        }
        try {
            stepResults.reject(
                    cycle.stepResult().orElseThrow().resultId(), reason);
        } catch (RuntimeException failure) {
            logFailure("stepResult.reject",
                    cycle.stepResult().orElseThrow()
                            .planId().value(),
                    1, failure);
        }
    }

    private List<String> completedResultFacts(String planId) {
        if (stepResults == null) {
            return List.of();
        }
        return stepResults.acceptedCompletedFacts(
                        new PlanId(planId)).stream()
                .map(value -> "acceptedStepResult="
                        + value.stepId().value()
                        + "; resultId=" + value.resultId()
                        + "; result="
                        + bounded(value.acceptedText().orElseThrow()))
                .toList();
    }

    private void reconcileAcceptedCompleted(
            List<V2AdaptiveTurnResponse.Step> timeline,
            Map<String, Integer> indexes,
            String planId) {
        if (stepResults == null) {
            return;
        }
        for (V2StepResultSnapshot result
                : stepResults.acceptedCompletedFacts(
                        new PlanId(planId))) {
            Integer row = indexes.get(result.stepId().value());
            if (row == null || row < 0 || row >= timeline.size()) {
                continue;
            }
            V2AdaptiveTurnResponse.Step current = timeline.get(row);
            timeline.set(row, new V2AdaptiveTurnResponse.Step(
                    current.index(), current.title(), "SUCCEEDED",
                    bounded(result.acceptedText().orElseThrow())));
        }
    }

    private Optional<String> terminalAcceptedText(
            String planId, V2AdaptiveCyclePort.CycleResult cycle) {
        if (stepResults == null
                || cycle.state()
                        != V2AdaptiveCyclePort.CycleResult.State
                                .PLAN_SUCCEEDED
                || !cycle.durableSucceeded()) {
            return Optional.empty();
        }
        return stepResults.latestAcceptedCompleted(
                        new PlanId(planId))
                .flatMap(V2StepResultSnapshot::acceptedText);
    }

    private static ReflectionStepResult reflectionStepResult(
            V2StepResultSnapshot value) {
        return new ReflectionStepResult(
                value.resultId(), value.stepId().value(),
                value.source().name(), value.proposedText(),
                value.proposedSha256(),
                value.evidenceReceiptIds().stream()
                        .map(receipt -> receipt.value()).toList());
    }

    private static List<ReceiptId> currentReceiptIds(
            Map<String, LinkedHashSet<ReceiptId>> receiptIdsByStep,
            String stepId) {
        if (stepId == null) {
            return List.of();
        }
        return List.copyOf(receiptIdsByStep.getOrDefault(
                stepId, new LinkedHashSet<>()));
    }

    private static void markFailure(
            List<V2AdaptiveTurnResponse.Step> steps, String code) {
        int selected = -1;
        for (int index = 0; index < steps.size(); index++) {
            if ("RUNNING".equals(steps.get(index).status())) {
                selected = index;
            }
        }
        if (selected < 0) {
            for (int index = 0; index < steps.size(); index++) {
                if ("PENDING".equals(steps.get(index).status())) {
                    selected = index;
                    break;
                }
            }
        }
        if (selected < 0) {
            return;
        }
        V2AdaptiveTurnResponse.Step current = steps.get(selected);
        steps.set(selected, new V2AdaptiveTurnResponse.Step(
                current.index(), current.title(), "FAILED", code));
    }

    private static void markTerminalSuccess(
            List<V2AdaptiveTurnResponse.Step> steps) {
        for (int index = 0; index < steps.size(); index++) {
            V2AdaptiveTurnResponse.Step step = steps.get(index);
            if ("RUNNING".equals(step.status())
                    || "PENDING".equals(step.status())) {
                steps.set(index, new V2AdaptiveTurnResponse.Step(
                        step.index(), step.title(),
                        "SUCCEEDED", step.detail()));
            }
        }
    }

    private static void recordAcceptedStepResult(
            List<V2AdaptiveTurnResponse.Step> timeline,
            Map<String, Integer> indexes,
            String stepId, String result) {
        Integer row = indexes.get(stepId);
        if (row == null || row < 0 || row >= timeline.size()) {
            return;
        }
        V2AdaptiveTurnResponse.Step current = timeline.get(row);
        timeline.set(row, new V2AdaptiveTurnResponse.Step(
                current.index(), current.title(),
                current.status(), bounded(result)));
    }

    private static ReflectionContext withNoProgressGuard(
            ReflectionContext context) {
        List<String> facts = new ArrayList<>(
                context.recentExecutionFacts());
        facts.add(NO_PROGRESS_GUARD);
        return new ReflectionContext(
                context.taskFrame(), context.currentPlan(),
                context.conversationContext(), context.completedFacts(),
                facts, context.unfinishedSteps(),
                context.currentStepResult());
    }

    private static ReflectionContext withReflectionDiagnostic(
            ReflectionContext context, String diagnostic) {
        List<String> facts = new ArrayList<>(
                context.recentExecutionFacts());
        facts.add("previousReflectionFault=" + diagnostic);
        return new ReflectionContext(
                context.taskFrame(), context.currentPlan(),
                context.conversationContext(), context.completedFacts(),
                facts, context.unfinishedSteps(),
                context.currentStepResult());
    }

    private record ReflectionResolution(
            ReflectionOutcome decision, String failureCode) {
    }

    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    public record Command(
            Long userId, Long turnId, String planId,
            List<V2AdaptiveTurnResponse.Step> steps,
            Map<String, String> toolBindings,
            Map<String, Integer> stepIndexes,
            ReflectionContext baseContext) {
        public Command {
            steps = List.copyOf(steps);
            toolBindings = Map.copyOf(toolBindings);
            stepIndexes = Map.copyOf(stepIndexes);
        }

        public Command(
                Long userId, Long turnId, String planId,
                List<V2AdaptiveTurnResponse.Step> steps,
                Map<String, String> toolBindings,
                ReflectionContext baseContext) {
            this(userId, turnId, planId, steps, toolBindings,
                    inferredIndexes(steps, toolBindings), baseContext);
        }

        ReflectionContext reflectionContext(
                List<String> accumulatedExecutionFacts,
                List<V2AdaptiveTurnResponse.Step> timeline,
                String activeStepId,
                ReflectionStepResult currentStepResult,
                List<String> acceptedStepResults,
                List<ReceiptId> currentStepReceiptIds) {
            List<String> currentFacts = new ArrayList<>(
                    accumulatedExecutionFacts);
            if (activeStepId != null) {
                currentFacts.add("activeStepId=" + activeStepId);
                Integer row = stepIndexes.get(activeStepId);
                if (row != null && row >= 0 && row < timeline.size()) {
                    currentFacts.add("activeStepTitle="
                            + bounded(timeline.get(row).title()));
                }
            }
            currentStepReceiptIds.forEach(receiptId ->
                    currentFacts.add(
                            "activeStepReceiptId=" + receiptId.value()));
            List<String> completedFacts = new ArrayList<>(
                    baseContext.completedFacts());
            completedFacts.addAll(acceptedStepResults);
            return new ReflectionContext(
                    baseContext.taskFrame(), baseContext.currentPlan(),
                    baseContext.conversationContext(),
                    List.copyOf(completedFacts),
                    List.copyOf(currentFacts),
                    timeline.stream()
                            .filter(step -> "PENDING".equals(step.status()))
                            .map(V2AdaptiveTurnResponse.Step::title).toList(),
                    currentStepResult);
        }

        private static Map<String, Integer> inferredIndexes(
                List<V2AdaptiveTurnResponse.Step> steps,
                Map<String, String> bindings) {
            Map<String, Integer> result = new LinkedHashMap<>();
            for (int index = 0; index < steps.size(); index++) {
                String title = steps.get(index).title();
                if (bindings.containsKey(title)) {
                    result.put(title, index);
                }
            }
            if (result.isEmpty() && steps.size() == bindings.size()) {
                int index = 0;
                for (String id : bindings.keySet()) {
                    result.put(id, index++);
                }
            }
            return result;
        }
    }
}
