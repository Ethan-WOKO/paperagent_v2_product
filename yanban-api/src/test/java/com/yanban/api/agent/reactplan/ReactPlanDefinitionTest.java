package com.yanban.api.agent.reactplan;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactPlanDefinitionTest {
    private static final BoundedExecutionHints HINTS =
            new BoundedExecutionHints(2, Duration.ofMinutes(1));

    @Test
    void acceptsAValidatedFutureMultiGoalDag() {
        ReactPlanDefinition definition = new ReactPlanDefinition(List.of(
                goal("inspect", List.of()),
                goal("execute", List.of("inspect")),
                goal("deliver", List.of("execute"))));

        assertEquals(3, definition.goals().size());
    }

    @Test
    void rejectsUnknownDependenciesAndCyclesBeforeAuthorityIdsExist() {
        assertThrows(IllegalArgumentException.class, () -> new ReactPlanDefinition(List.of(
                goal("execute", List.of("missing")))));
        assertThrows(IllegalArgumentException.class, () -> new ReactPlanDefinition(List.of(
                goal("a", List.of("b")),
                goal("b", List.of("a")))));
    }

    private static ReactPlanGoal goal(String key, List<String> dependencies) {
        return new ReactPlanGoal(
                key,
                key + " objective",
                key + " outcome",
                dependencies,
                List.of(key + " done"),
                List.of(),
                HINTS);
    }
}
