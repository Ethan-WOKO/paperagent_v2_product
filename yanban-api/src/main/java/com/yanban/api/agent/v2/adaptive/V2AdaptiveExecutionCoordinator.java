package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.adaptive.reflection.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                    "SANDBOX_EXECUTION_UNAVAILABLE", 0, 0);
        }
        List<V2AdaptiveTurnResponse.Step> timeline =
                new ArrayList<>(command.steps());
        Object pendingReplan = null;
        int replanCount = 0;
        for (int index = 1; index <= MAX_CYCLES; index++) {
            V2AdaptiveCyclePort.CycleResult cycle = cycles.executeOne(
                    new V2AdaptiveCyclePort.CycleCommand(
                            command.userId(), command.turnId(),
                            command.planId(), index, pendingReplan));
            pendingReplan = null;
            timeline.add(new V2AdaptiveTurnResponse.Step(
                    timeline.size() + 1,
                    cycle.stepId() == null ? "执行计划" : cycle.stepId(),
                    cycle.state() == V2AdaptiveCyclePort.CycleResult.State.FAILED
                            ? "FAILED" : "SUCCEEDED",
                    bounded(cycle.detail())));
            ReflectionOutcome decision;
            try {
                decision = parser.parse(reflections.reflect(
                        command.reflectionContext(cycle, timeline)));
            } catch (RuntimeException invalid) {
                return failed(timeline,
                        "REFLECTION_INVALID", index, replanCount);
            }
            if (decision.decision() == ReflectionAction.COMPLETE) {
                if (!cycle.durableSucceeded()
                        || cycle.state()
                        != V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED) {
                    return failed(timeline,
                            "PREMATURE_COMPLETE", index, replanCount);
                }
                return new V2AdaptiveExecutionResult(
                        "SUCCEEDED", timeline, decision.finalText(), null,
                        index, replanCount, replanCount);
            }
            if (decision.decision() == ReflectionAction.FAIL) {
                return failed(timeline, "REFLECTION_FAILED",
                        index, replanCount);
            }
            if (cycle.durableSucceeded()) {
                return failed(timeline, "TERMINAL_DECISION_INVALID",
                        index, replanCount);
            }
            if (decision.decision() == ReflectionAction.REPLAN) {
                if (++replanCount > MAX_REPLANS) {
                    return failed(timeline, "REPLAN_LIMIT_EXCEEDED",
                            index, replanCount);
                }
                pendingReplan = decision;
            }
        }
        return failed(timeline, "CYCLE_LIMIT_EXCEEDED",
                MAX_CYCLES, replanCount);
    }

    private static V2AdaptiveExecutionResult failed(
            List<V2AdaptiveTurnResponse.Step> steps, String code,
            int reflections, int replans) {
        return new V2AdaptiveExecutionResult(
                "FAILED", steps, null, code,
                reflections, replans, replans);
    }

    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    public record Command(
            Long userId, Long turnId, String planId,
            List<V2AdaptiveTurnResponse.Step> steps,
            Map<String, String> toolBindings,
            ReflectionContext baseContext) {
        public Command {
            steps = List.copyOf(steps);
            toolBindings = Map.copyOf(toolBindings);
        }

        ReflectionContext reflectionContext(
                V2AdaptiveCyclePort.CycleResult cycle,
                List<V2AdaptiveTurnResponse.Step> timeline) {
            List<String> facts = new ArrayList<>(
                    baseContext.recentExecutionFacts());
            facts.add(cycle.state() + ": " + bounded(cycle.detail()));
            return new ReflectionContext(
                    baseContext.taskFrame(), baseContext.currentPlan(),
                    baseContext.conversationContext(),
                    baseContext.completedFacts(), facts,
                    timeline.stream()
                            .filter(step -> "PENDING".equals(step.status()))
                            .map(V2AdaptiveTurnResponse.Step::title).toList());
        }
    }
}
