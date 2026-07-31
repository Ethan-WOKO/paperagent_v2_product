package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.adaptive.reflection.*;
import java.util.*;

/** Bounded policy around exactly one durable Runtime cycle per reflection. */
public final class V2AdaptiveExecutionCoordinator {
    static final int MAX_CYCLES = 8;
    static final int MAX_REPLANS = 3;
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
                return failed(timeline, "CYCLE_EXECUTION_EXCEPTION",
                        executionCycles, replanCount, repairCount);
            }
            pendingReplan = null;
            V2AdaptiveCyclePort.Action completedAction = nextAction;
            nextAction = V2AdaptiveCyclePort.Action.EXECUTE;
            updateExisting(timeline, stepIndexes, cycle);
            appendExecutionFacts(accumulatedExecutionFacts, cycle);
            if (cycle.receiptBacked() && cycle.stepId() != null) {
                receiptBackedSteps.add(cycle.stepId());
            }
            if (cycle.replanAuthority() != null
                    && pendingDecision != null) {
                applyPersistedReplan(
                        timeline, stepIndexes, cycle, pendingDecision);
                if (pendingRepair) {
                    repairCount++;
                }
                pendingRepair = false;
                pendingDecision = null;
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
                            accumulatedExecutionFacts, timeline);
            ReflectionResolution reflection = reflect(
                    reflectionContext);
            if (reflection.failureCode() != null) {
                return failed(timeline, reflection.failureCode(),
                        executionCycles, replanCount, repairCount);
            }
            decision = reflection.decision();
            if (requiresNoProgressReflection(cycle, decision)) {
                ReflectionResolution reconsidered = reflect(
                        withNoProgressGuard(reflectionContext));
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
            V2AdaptiveCyclePort.CycleResult cycle) {
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
        } else {
            status = "SUCCEEDED";
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

    private ReflectionResolution reflect(ReflectionContext initial) {
        ReflectionContext context = initial;
        String failureCode = "REFLECTION_PROVIDER_EXCEPTION";
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw;
            try {
                raw = reflections.reflect(context);
            } catch (RuntimeException providerFailure) {
                failureCode = "REFLECTION_PROVIDER_EXCEPTION";
                context = withReflectionDiagnostic(context, failureCode);
                continue;
            }
            try {
                return new ReflectionResolution(
                        parser.parse(raw), null);
            } catch (ReflectionParseException invalid) {
                failureCode = "REFLECTION_PARSE_INVALID";
                context = withReflectionDiagnostic(context, failureCode);
            } catch (RuntimeException parserFailure) {
                failureCode = "REFLECTION_PARSER_EXCEPTION";
                context = withReflectionDiagnostic(context, failureCode);
            }
        }
        return new ReflectionResolution(null, failureCode);
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
                List<V2AdaptiveTurnResponse.Step> timeline) {
            return new ReflectionContext(
                    baseContext.taskFrame(), baseContext.currentPlan(),
                    baseContext.conversationContext(),
                    baseContext.completedFacts(),
                    List.copyOf(accumulatedExecutionFacts),
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
