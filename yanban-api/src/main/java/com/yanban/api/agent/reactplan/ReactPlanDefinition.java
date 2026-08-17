package com.yanban.api.agent.reactplan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A bounded goal DAG. P1 creates one goal; the shape intentionally permits a
 * later validated plan.update without changing the execution contract.
 */
public record ReactPlanDefinition(List<ReactPlanGoal> goals) {
    private static final int MAX_GOALS = 32;

    public ReactPlanDefinition {
        Objects.requireNonNull(goals, "goals");
        goals = List.copyOf(goals);
        if (goals.isEmpty() || goals.size() > MAX_GOALS) {
            throw new IllegalArgumentException("goals must contain between 1 and " + MAX_GOALS + " entries");
        }
        Map<String, ReactPlanGoal> byKey = new HashMap<>();
        for (ReactPlanGoal goal : goals) {
            if (byKey.put(goal.key(), goal) != null) {
                throw new IllegalArgumentException("duplicate goal key: " + goal.key());
            }
        }
        for (ReactPlanGoal goal : goals) {
            for (String dependency : goal.dependsOn()) {
                if (!byKey.containsKey(dependency)) {
                    throw new IllegalArgumentException("unknown goal dependency: " + dependency);
                }
                if (dependency.equals(goal.key())) {
                    throw new IllegalArgumentException("goal cannot depend on itself: " + dependency);
                }
            }
        }
        requireAcyclic(byKey);
    }

    private static void requireAcyclic(Map<String, ReactPlanGoal> goals) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String key : goals.keySet()) {
            visit(key, goals, visiting, visited);
        }
    }

    private static void visit(
            String key,
            Map<String, ReactPlanGoal> goals,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(key)) {
            return;
        }
        if (!visiting.add(key)) {
            throw new IllegalArgumentException("goal graph contains a cycle at: " + key);
        }
        for (String dependency : goals.get(key).dependsOn()) {
            visit(dependency, goals, visiting, visited);
        }
        visiting.remove(key);
        visited.add(key);
    }
}
