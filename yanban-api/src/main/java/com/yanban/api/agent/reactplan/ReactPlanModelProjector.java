package com.yanban.api.agent.reactplan;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Projects persisted authority facts into the bounded model context. */
@Component
public class ReactPlanModelProjector {

    public ReactPlanModelProjection project(PersistedPlanBootstrap bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        return project(bootstrap.taskFrame(), bootstrap.plan());
    }

    ReactPlanModelProjection project(TaskFrame taskFrame, Plan plan) {
        Objects.requireNonNull(taskFrame, "taskFrame");
        Objects.requireNonNull(plan, "plan");
        List<PlanStep> steps = plan.latestRevision().steps();
        Map<io.paperagent.v2.contracts.PlanStepId, String> localKeys = localKeys(steps);
        List<ReactPlanGoal> goals = steps.stream()
                .map(step -> new ReactPlanGoal(
                        localKeys.get(step.id()),
                        step.intent(),
                        step.expectedOutcome(),
                        step.dependencies().stream().map(localKeys::get).toList(),
                        step.completionCriteria(),
                        taskFrame.constraints(),
                        step.executionHints()))
                .toList();
        Set<String> capabilities = taskFrame.executionProfile().capabilities().stream()
                .map(Capability::name)
                .collect(Collectors.toUnmodifiableSet());
        return new ReactPlanModelProjection(
                goals,
                taskFrame.targets(),
                taskFrame.deliverables(),
                taskFrame.constraints(),
                capabilities);
    }

    private static Map<io.paperagent.v2.contracts.PlanStepId, String> localKeys(
            List<PlanStep> steps) {
        Map<io.paperagent.v2.contracts.PlanStepId, String> keys = new LinkedHashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            keys.put(steps.get(index).id(), index == 0
                    ? DeterministicReactPlanDraftFactory.GOAL_KEY
                    : "goal-" + (index + 1));
        }
        return keys;
    }
}
