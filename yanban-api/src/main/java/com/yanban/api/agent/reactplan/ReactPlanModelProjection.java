package com.yanban.api.agent.reactplan;

import java.util.List;
import java.util.Set;

/**
 * The complete authority-safe payload visible to the model. It deliberately
 * contains no Plan, Step, Event, ToolCall, Receipt, user, session, or secret IDs.
 */
public record ReactPlanModelProjection(
        List<ReactPlanGoal> goals,
        List<String> targets,
        List<String> deliverables,
        List<String> constraints,
        Set<String> capabilities) {

    public ReactPlanModelProjection {
        goals = List.copyOf(goals);
        targets = List.copyOf(targets);
        deliverables = List.copyOf(deliverables);
        constraints = List.copyOf(constraints);
        capabilities = Set.copyOf(capabilities);
    }
}
