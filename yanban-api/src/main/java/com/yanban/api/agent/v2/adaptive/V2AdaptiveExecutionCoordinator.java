package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.adaptive.reflection.*;
import java.util.*;

/** Bounded policy around exactly one durable Runtime cycle per reflection. */
public final class V2AdaptiveExecutionCoordinator {
    static final int MAX_CYCLES = 8;
    static final int MAX_REPLANS = 3;
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
        if (command.toolBindings().containsValue("sandbox.execute")) {
            return failed(command.steps(),
                    "SANDBOX_EXECUTION_UNAVAILABLE", 0, 0, 0);
        }
        List<V2AdaptiveTurnResponse.Step> timeline =
                new ArrayList<>(command.steps());
        Map<String, Integer> stepIndexes =
                new LinkedHashMap<>(command.stepIndexes());
        Set<String> requiredReceiptSteps =
                new LinkedHashSet<>(command.toolBindings().keySet());
        Set<String> receiptBackedSteps = new LinkedHashSet<>();
        Object pendingReplan = null;
        ReflectionOutcome pendingDecision = null;
        boolean pendingRepair = false;
        int replanCount = 0;
        int repairCount = 0;
        for (int index = 1; index <= MAX_CYCLES; index++) {
            V2AdaptiveCyclePort.CycleResult cycle = cycles.executeOne(
                    new V2AdaptiveCyclePort.CycleCommand(
                            command.userId(), command.turnId(),
                            command.planId(), index, pendingReplan));
            pendingReplan = null;
            updateExisting(timeline, stepIndexes, cycle);
            if (cycle.receiptBacked() && cycle.stepId() != null) {
                receiptBackedSteps.add(cycle.stepId());
            }
            if (cycle.replanAuthority() != null
                    && pendingDecision != null) {
                applyPersistedReplan(
                        timeline, stepIndexes, requiredReceiptSteps,
                        cycle, pendingDecision);
                if (pendingRepair) {
                    repairCount++;
                }
                pendingRepair = false;
                pendingDecision = null;
            }

            ReflectionOutcome decision;
            try {
                decision = parser.parse(reflections.reflect(
                        command.reflectionContext(cycle, timeline)));
            } catch (RuntimeException invalid) {
                return failed(timeline,
                        "REFLECTION_INVALID", index, replanCount,
                        repairCount);
            }
            if (decision.decision() == ReflectionAction.COMPLETE) {
                if (!cycle.durableSucceeded()
                        || cycle.state()
                        != V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED
                        || !receiptBackedSteps.containsAll(
                                requiredReceiptSteps)) {
                    return failed(timeline,
                            "PREMATURE_COMPLETE", index, replanCount,
                            repairCount);
                }
                return new V2AdaptiveExecutionResult(
                        "SUCCEEDED", timeline, decision.finalText(), null,
                        index, replanCount, repairCount);
            }
            if (decision.decision() == ReflectionAction.FAIL) {
                return failed(timeline, "REFLECTION_FAILED",
                        index, replanCount, repairCount);
            }
            if (cycle.durableSucceeded()) {
                return failed(timeline, "TERMINAL_DECISION_INVALID",
                        index, replanCount, repairCount);
            }
            if (decision.decision() == ReflectionAction.REPLAN) {
                if (++replanCount > MAX_REPLANS) {
                    return failed(timeline, "REPLAN_LIMIT_EXCEEDED",
                            index, replanCount, repairCount);
                }
                pendingReplan = decision;
                pendingDecision = decision;
                pendingRepair = cycle.failedReceipt();
            }
        }
        return failed(timeline, "CYCLE_LIMIT_EXCEEDED",
                MAX_CYCLES, replanCount, repairCount);
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
        String status = cycle.state()
                == V2AdaptiveCyclePort.CycleResult.State.FAILED
                || cycle.failedReceipt() ? "FAILED" : "SUCCEEDED";
        timeline.set(row, new V2AdaptiveTurnResponse.Step(
                existing.index(), existing.title(), status,
                bounded(cycle.detail())));
    }

    private static void applyPersistedReplan(
            List<V2AdaptiveTurnResponse.Step> timeline,
            Map<String, Integer> indexes,
            Set<String> requiredReceiptSteps,
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
            if (replacement.internalToolId() != null) {
                requiredReceiptSteps.add(id);
            }
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
                V2AdaptiveCyclePort.CycleResult cycle,
                List<V2AdaptiveTurnResponse.Step> timeline) {
            List<String> facts = new ArrayList<>(
                    baseContext.recentExecutionFacts());
            if (cycle.authoritativeFacts().isEmpty()) {
                facts.add(cycle.state() + ": " + bounded(cycle.detail()));
            } else {
                facts.addAll(cycle.authoritativeFacts());
            }
            return new ReflectionContext(
                    baseContext.taskFrame(), baseContext.currentPlan(),
                    baseContext.conversationContext(),
                    baseContext.completedFacts(), facts,
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
