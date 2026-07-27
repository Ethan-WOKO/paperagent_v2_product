package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ViolationCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitialPlanCanonicalValidationTest {
    @Test
    void duplicateStepIdsPropagateCanonicalFailureUnwrapped() {
        PlanStep duplicate = PlanningTestFixtures.step(
                new PlanStepId("step-duplicate"),
                Set.of());

        assertCanonicalViolation(
                new InitialPlanDraft("duplicate plan", List.of(duplicate, duplicate)),
                ViolationCode.DUPLICATE_ID,
                "planRevision.steps");
    }

    @Test
    void unknownDependencyPropagatesCanonicalFailureUnwrapped() {
        PlanStep step = PlanningTestFixtures.step(
                new PlanStepId("step-unknown-dependency"),
                Set.of(new PlanStepId("step-missing")));

        assertCanonicalViolation(
                new InitialPlanDraft("unknown dependency plan", List.of(step)),
                ViolationCode.UNKNOWN_STEP_DEPENDENCY,
                "planStep.dependencies");
    }

    @Test
    void dependencyCyclePropagatesCanonicalFailureUnwrapped() {
        PlanStepId firstId = new PlanStepId("step-cycle-1");
        PlanStepId secondId = new PlanStepId("step-cycle-2");
        PlanStep first = PlanningTestFixtures.step(firstId, Set.of(secondId));
        PlanStep second = PlanningTestFixtures.step(secondId, Set.of(firstId));

        assertCanonicalViolation(
                new InitialPlanDraft("cyclic plan", List.of(first, second)),
                ViolationCode.STEP_DEPENDENCY_CYCLE,
                "planRevision.steps");
    }

    @Test
    void blankReasonPropagatesCanonicalFailureUnwrapped() {
        assertCanonicalViolation(
                new InitialPlanDraft(" ", PlanningTestFixtures.twoStepDraft().steps()),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "planRevision.reason");
    }

    private static ContractViolationException assertCanonicalViolation(
            InitialPlanDraft draft,
            ViolationCode expectedCode,
            String expectedPath) {
        ContractViolationException exception = assertThrows(
                ContractViolationException.class,
                () -> new DeterministicInitialPlanFreezer().freeze(
                        PlanningTestFixtures.request(draft)));
        assertEquals(expectedCode, exception.primaryCode());
        assertEquals(expectedPath, exception.violations().get(0).path());
        assertNull(exception.getCause());
        return exception;
    }
}
