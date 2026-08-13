package io.paperagent.v2.chain;

import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedTaskRequirementsTest {
    private static final String CONDITION = "the declared validation succeeds";

    @Test
    void persistentPlanBindsEveryTypedValidationRequirementToExactlyOneStep() {
        ProposalFields.TaskFrameDraft frame = frame(requirement("validation-1"));
        ProposalFields.StepDraft step = step(List.of("validation-1"), CONDITION);

        PlannerPayload.PersistentPlan plan = new PlannerPayload.PersistentPlan(
                frame, List.of(coverage()),
                new ProposalFields.PlanDraft(List.of(step)), List.of(), null);

        assertEquals(List.of("validation-1"), plan.initialPlan().steps().get(0)
                .validationRequirementIds());
        assertEquals(PublishRequirement.NOT_REQUIRED,
                plan.taskFrameDraft().requirements().publishRequirement());
    }

    @Test
    void persistentPlanRequiresDeclaredRoutingBoundaryAndNonemptyTarget() {
        ProposalFields.TaskFrameDraft frame = frame(
                requirement("validation-1"));
        ProposalFields.PlanDraft plan = new ProposalFields.PlanDraft(List.of(
                step(List.of("validation-1"), CONDITION)));

        IllegalArgumentException route = assertThrows(
                IllegalArgumentException.class,
                () -> new PlannerPayload.PersistentPlan(
                        frame, new ProposalFields.RoutingBoundary(
                        false, false, false, false),
                        List.of(coverage()), plan, List.of(), null));
        assertTrue(route.getMessage().contains(
                "PERSISTENT_ROUTE_WITHOUT_REQUIREMENT"));

        assertThrows(IllegalArgumentException.class,
                () -> new ProposalFields.TaskFrameDraft(
                        "objective", List.of(), List.of("deliverable"),
                        List.of(), "version", "tier",
                        TaskRequirements.explicit(List.of(),
                                PublishRequirement.NOT_REQUIRED)));
    }

    @Test
    void persistentPlanRejectsMissingUnknownDuplicateAndConditionDriftBindings() {
        ProposalFields.TaskFrameDraft frame = frame(requirement("validation-1"));
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class, () -> persistent(
                        frame, step(List.of(), CONDITION)));
        assertTrue(missing.getMessage().contains(
                "missing requirementIds=[validation-1]"));
        assertThrows(IllegalArgumentException.class, () -> persistent(
                frame, step(List.of("unknown"), CONDITION)));
        assertThrows(IllegalArgumentException.class, () -> new ProposalFields.PlanDraft(List.of(
                step(List.of("validation-1"), CONDITION),
                new ProposalFields.StepDraft("step-2", 2, "objective", List.of("step-1"),
                        List.of(CONDITION), List.of("scope"), List.of("deliverable"),
                        false, null, List.of(), List.of("validation-1")))));
        assertThrows(IllegalArgumentException.class, () -> persistent(
                frame, step(List.of("validation-1"), "different condition")));
    }

    @Test
    void requirementsRejectLegacyAndDuplicateIdsInNewPlannerDraft() {
        assertThrows(IllegalArgumentException.class, () -> new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"), List.of(),
                "version", "tier", TaskRequirements.legacyUnspecified()));
        assertThrows(RuntimeException.class, () -> TaskRequirements.explicit(List.of(
                requirement("duplicate"), requirement("duplicate")),
                PublishRequirement.NOT_REQUIRED));
    }

    @Test
    void canonicalPlanStepPreservesValidationBindingWithoutCandidateSideEffect() {
        PlanStep step = new PlanStep(
                new PlanStepId("step-1"), "verify", "verified result",
                Set.of(), List.of(CONDITION),
                new BoundedExecutionHints(2, Duration.ofMinutes(1)),
                List.of(), false, null, List.of("validation-1"));

        assertEquals(List.of("validation-1"), step.validationRequirementIds());
        assertEquals(false, step.mayChangeCandidate());
    }

    @Test
    void candidateMutationAllowsValidationOnDependentLaterStep() {
        ProposalFields.StepDraft candidateChange = new ProposalFields.StepDraft(
                "change", 1, "change candidate", List.of(), List.of(CONDITION),
                List.of("scope"), List.of("candidate"), true, null,
                List.of(), List.of());
        ProposalFields.StepDraft validation = new ProposalFields.StepDraft(
                "validate", 2, "validate candidate", List.of("change"),
                List.of(CONDITION), List.of("scope"), List.of("receipt"),
                false, CONDITION, List.of(), List.of("validation-1"));
        ProposalFields.TaskFrameDraft frame = new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"), List.of(),
                "version", "tier", TaskRequirements.explicit(List.of(
                new ValidationRequirement("validation-1",
                        ValidationSubject.CANDIDATE, CONDITION)),
                PublishRequirement.NOT_REQUIRED));

        PlannerPayload.PersistentPlan plan = new PlannerPayload.PersistentPlan(
                frame, List.of(coverage()), new ProposalFields.PlanDraft(
                List.of(candidateChange, validation)), List.of(), null);

        assertEquals(List.of("validation-1"), plan.initialPlan().steps().get(1)
                .validationRequirementIds());
        assertEquals(true, plan.initialPlan().steps().get(0).mayChangeCandidate());
        assertThrows(IllegalArgumentException.class, () ->
                new PlannerPayload.PersistentPlan(frame, List.of(coverage()),
                        new ProposalFields.PlanDraft(List.of(
                                new ProposalFields.StepDraft(
                                        "change", 1, "change candidate",
                                        List.of(), List.of(CONDITION),
                                        List.of("scope"), List.of("candidate"),
                                        true, CONDITION, List.of(),
                                        List.of("validation-1")))),
                        List.of(), null));

        ProposalFields.TaskFrameDraft noValidationFrame = new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"), List.of(),
                "version", "tier", TaskRequirements.explicit(
                List.of(), PublishRequirement.NOT_REQUIRED));
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class, () ->
                new PlannerPayload.PersistentPlan(noValidationFrame,
                        List.of(coverage()), new ProposalFields.PlanDraft(List.of(
                        new ProposalFields.StepDraft(
                                "change", 1, "change candidate", List.of(),
                                List.of("changed"), List.of("scope"),
                                List.of("candidate"), true, null,
                                List.of(), List.of()))), List.of(), null));
        assertTrue(missing.getMessage().contains(
                "actual CANDIDATE requirement count=0"));
        assertThrows(IllegalArgumentException.class, () ->
                new PlannerPayload.PersistentPlan(frame, List.of(coverage()),
                        new ProposalFields.PlanDraft(List.of(
                                candidateChange,
                                new ProposalFields.StepDraft(
                                        "unrelated-validation", 2,
                                        "validate without dependency",
                                        List.of(), List.of(CONDITION),
                                        List.of("scope"), List.of("receipt"),
                                        false, CONDITION, List.of(),
                                        List.of("validation-1")))),
                        List.of(), null));
    }

    @Test
    void legacyCandidateConditionDoesNotCreateAValidationRequirement() {
        ProposalFields.TaskFrameDraft frame = new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"), List.of(),
                "version", "tier", TaskRequirements.explicit(
                List.of(), PublishRequirement.NOT_REQUIRED));
        ProposalFields.StepDraft step = new ProposalFields.StepDraft(
                "step-1", 1, "inspect", List.of(), List.of("inspected"),
                List.of("scope"), List.of("result"), false, "inspected",
                List.of(), List.of());

        PlannerPayload.PersistentPlan plan = new PlannerPayload.PersistentPlan(
                frame, List.of(coverage()), new ProposalFields.PlanDraft(
                List.of(step)), List.of(), null);

        assertEquals(List.of(), plan.initialPlan().steps().get(0)
                .validationRequirementIds());
    }

    private static PlannerPayload.PersistentPlan persistent(
            ProposalFields.TaskFrameDraft frame,
            ProposalFields.StepDraft step) {
        return new PlannerPayload.PersistentPlan(frame, List.of(coverage()),
                new ProposalFields.PlanDraft(List.of(step)), List.of(), null);
    }

    private static ProposalFields.TaskFrameDraft frame(
            ValidationRequirement requirement) {
        return new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"), List.of(),
                "version", "tier", TaskRequirements.explicit(
                List.of(requirement), PublishRequirement.NOT_REQUIRED));
    }

    private static ValidationRequirement requirement(String id) {
        return new ValidationRequirement(id, ValidationSubject.ACTION_RECEIPT, CONDITION);
    }

    private static ProposalFields.StepDraft step(
            List<String> requirementIds,
            String completionCondition) {
        return new ProposalFields.StepDraft(
                "step-1", 1, "objective", List.of(),
                List.of(completionCondition), List.of("scope"),
                List.of("deliverable"), false, null, List.of(), requirementIds);
    }

    private static ProposalFields.RequirementCoverage coverage() {
        return new ProposalFields.RequirementCoverage(
                "deliverable", ProposalFields.RequirementStatus.PLANNED, List.of());
    }
}
