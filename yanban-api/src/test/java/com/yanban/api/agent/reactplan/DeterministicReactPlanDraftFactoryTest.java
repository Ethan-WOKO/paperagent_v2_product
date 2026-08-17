package com.yanban.api.agent.reactplan;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import io.paperagent.v2.contracts.PlanStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicReactPlanDraftFactoryTest {
    private final DeterministicReactPlanDraftFactory factory =
            new DeterministicReactPlanDraftFactory();

    @Test
    void createsOneProductOwnedStepWithoutPlannerOutput() {
        ProductPersistentPlanBootstrapCommand result =
                factory.create("AGENT_TURN:42", ReactPlanTestFixtures.command());

        assertEquals(DeterministicReactPlanDraftFactory.REASON, result.initialPlanDraft().reason());
        assertEquals(1, result.initialPlanDraft().steps().size());
        PlanStep step = result.initialPlanDraft().steps().get(0);
        assertEquals("Compile Sort.java and explain the result", step.intent());
        assertEquals(DeterministicReactPlanDraftFactory.COMPLETION_CRITERIA,
                step.completionCriteria());
        assertEquals(2, step.executionHints().maxAttempts());
        assertTrue(step.dependencies().isEmpty());
    }

    @Test
    void exactRunReplayProducesTheSameStepIdentity() {
        PlanStep first = factory.create("AGENT_TURN:42", ReactPlanTestFixtures.command())
                .initialPlanDraft().steps().get(0);
        PlanStep replay = factory.create("AGENT_TURN:42", ReactPlanTestFixtures.command())
                .initialPlanDraft().steps().get(0);
        PlanStep anotherRun = factory.create("AGENT_TURN:43", ReactPlanTestFixtures.command())
                .initialPlanDraft().steps().get(0);

        assertEquals(first, replay);
        assertNotEquals(first.id(), anotherRun.id());
    }

    @Test
    void p1DefinitionIsMultiGoalCapableButFactoryCreatesOneGoal() {
        ReactPlanDefinition definition = factory.definition(ReactPlanTestFixtures.command());

        assertEquals(1, definition.goals().size());
        assertEquals(DeterministicReactPlanDraftFactory.GOAL_KEY,
                definition.goals().get(0).key());
    }
}
