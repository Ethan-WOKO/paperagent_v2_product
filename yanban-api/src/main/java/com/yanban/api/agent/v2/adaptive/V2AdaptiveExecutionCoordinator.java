package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.adaptive.reflection.*;
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
    private static final String REFLECTION_WITH_DURABLE_SUCCESS =
            "MODEL_CHOSE_REFLECTION_WITH_DURABLE_SUCCESS";
    private static final String NO_PROGRESS_GUARD =
            "noProgressGuard=CONTINUE would replay the same "
                    + "already-successful intent and create no new durable "
                    + "evidence; choose COMPLETE if the current Step is "
                    + "satisfied, REPLAN if the approach must change, or "
                    + "FAIL if execution cannot proceed";
    private final V2AdaptiveCyclePort cycles;
    private final ReflectionProvider reflections;
    private final StrictReflectionDecisionParser parser;

    public V2AdaptiveExecutionCoordinator(
            V2AdaptiveCyclePort cycles, ReflectionProvider reflections,
            StrictReflectionDecisionParser parser) {
        this.cycles = cycles;
        this.reflections = reflections;
        this.parser = parser;
    }

    public V2AdaptiveExecutionResult execute(Command command) {
        List<V2AdaptiveTurnResponse.Step> timeline =
                new ArrayList<>(command.steps());
        Map<String, Integer> stepIndexes =
                new LinkedHashMap<>(command.stepIndexes());
        Set<String> receiptBackedSteps = new LinkedHashSet<>();
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
                    == V2AdaptiveCyclePort.CycleResult.State
                            .RECOVERY_PENDING) {
                return new V2AdaptiveExecutionResult(
                        "RUNNING", timeline, null, null,
                        executionCycles, replanCount, repairCount);
            }

            ReflectionOutcome decision;
            ReflectionContext reflectionContext =
                    command.reflectionContext(
                            accumulatedExecutionFacts, timeline,
                            cycle.stepId());
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
                        || !receiptBackedSteps.contains(cycle.stepId())) {
                    return failed(timeline,
                            "PREMATURE_COMPLETE", executionCycles, replanCount,
                            repairCount);
                }
                acceptedFinalText = decision.finalText();
                nextAction = V2AdaptiveCyclePort.Action.COMPLETE_STEP;
                continue;
            }
            if (decision.decision() == ReflectionAction.FAIL) {
                return failed(timeline, "REFLECTION_FAILED",
                        executionCycles, replanCount, repairCount);
            }
            if (cycle.durableSucceeded()) {
                return failed(timeline, "TERMINAL_DECISION_INVALID",
                        executionCycles, replanCount, repairCount);
            }
            if (decision.decision() == ReflectionAction.REPLAN) {
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
        timeline.set(row, new V2AdaptiveTurnResponse.Step(
                existing.index(), existing.title(), status,
                bounded(cycle.detail())));
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
                && cycle.receiptBacked()
                && !cycle.failedReceipt()
                && cycle.authoritativeFacts().contains(
                        REFLECTION_WITH_DURABLE_SUCCESS);
    }

    private static ReflectionContext withNoProgressGuard(
            ReflectionContext context) {
        List<String> facts = new ArrayList<>(
                context.recentExecutionFacts());
        facts.add(NO_PROGRESS_GUARD);
        return new ReflectionContext(
                context.taskFrame(), context.currentPlan(),
                context.conversationContext(), context.completedFacts(),
                facts, context.unfinishedSteps());
    }

    private static ReflectionContext withReflectionDiagnostic(
            ReflectionContext context, String diagnostic) {
        List<String> facts = new ArrayList<>(
                context.recentExecutionFacts());
        facts.add("previousReflectionFault=" + diagnostic);
        return new ReflectionContext(
                context.taskFrame(), context.currentPlan(),
                context.conversationContext(), context.completedFacts(),
                facts, context.unfinishedSteps());
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
                String activeStepId) {
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
            return new ReflectionContext(
                    baseContext.taskFrame(), baseContext.currentPlan(),
                    baseContext.conversationContext(),
                    baseContext.completedFacts(),
                    List.copyOf(currentFacts),
                    timeline.stream()
                            .filter(step -> "PENDING".equals(step.status()))
                            .map(V2AdaptiveTurnResponse.Step::title).toList());
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
