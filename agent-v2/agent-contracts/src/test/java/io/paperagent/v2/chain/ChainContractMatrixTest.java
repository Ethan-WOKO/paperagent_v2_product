package io.paperagent.v2.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChainContractMatrixTest {
    @Test
    void freezesThirteenModulesAcrossFourRoles() {
        assertEquals(13, ChainContextModule.values().length);
        assertEquals(4, ChainRole.values().length);
        assertEquals(52, ChainContextModule.values().length * ChainRole.values().length);
        for (int index = 0; index < ChainContextModule.values().length; index++) {
            assertEquals(index + 1, ChainContextModule.values()[index].ordinalCode());
        }
        assertEquals(ChainContextModuleStatus.PRESENT,
                ChainContextModuleStatus.valueOf("PRESENT"));
        assertEquals(ChainContextModuleStatus.EMPTY,
                ChainContextModuleStatus.valueOf("EMPTY"));
    }

    @Test
    void freezesTwentyFiveRoleSpecificKindsAndSealedPayloads() {
        Map<ChainRole, Integer> expected = Map.of(
                ChainRole.PLANNER, 7,
                ChainRole.EXECUTOR, 4,
                ChainRole.REFLECTOR, 8,
                ChainRole.ANSWER, 6);
        Map<ChainRole, Integer> actual = new EnumMap<>(ChainRole.class);
        for (ChainProposalKind kind : ChainProposalKind.values()) {
            actual.merge(kind.role(), 1, Integer::sum);
            assertEquals(kind, ChainProposalKind.resolve(kind.role(), kind.wireName()));
        }
        assertEquals(25, ChainProposalKind.values().length);
        assertEquals(expected, actual);
        assertEquals(7, PlannerPayload.class.getPermittedSubclasses().length);
        assertEquals(4, ExecutorPayload.class.getPermittedSubclasses().length);
        assertEquals(8, ReflectorPayload.class.getPermittedSubclasses().length);
        assertEquals(6, AnswerPayload.class.getPermittedSubclasses().length);
        assertEquals(4, ChainProposalPayload.class.getPermittedSubclasses().length);
        assertEquals(25, new HashSet<>(allPayloads().stream().map(ChainProposalPayload::kind).toList()).size());
    }

    @Test
    void providerEnvelopeRejectsUnknownSchemaAndCrossKindPayload() {
        ChainProposalPayload direct = allPayloads().get(0);
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRoleOutput("2", "DIRECT_ROUTE", direct));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRoleOutput("1", "TOOL_ACTION", direct));
        ProviderRoleOutput output = new ProviderRoleOutput("1", "DIRECT_ROUTE", direct);
        assertEquals(ChainRole.PLANNER, output.payload().role());
        assertEquals(List.of("schemaVersion", "kind", "payload"), Arrays.stream(
                ProviderRoleOutput.class.getRecordComponents()).map(component -> component.getName()).toList());
        assertFalse(Arrays.stream(output.payload().getClass().getRecordComponents())
                .map(component -> component.getName()).anyMatch("content"::equals));
    }

    @Test
    void everyKindHasTypedStructureAndOnlyPlannerExecutorExposeFlatGapValidation() {
        for (ChainProposalPayload payload : allPayloads()) {
            assertTrue(payload.getClass().isRecord());
            assertTrue(payload.getClass().getRecordComponents().length > 0);
            boolean hasGapField = Arrays.stream(payload.getClass().getRecordComponents())
                    .map(component -> component.getName())
                    .anyMatch("gapValidation"::equals);
            assertEquals(payload.role() == ChainRole.PLANNER || payload.role() == ChainRole.EXECUTOR, hasGapField);
        }
    }

    @Test
    void taskFramePlanStepCoverageAndAssessmentsRejectAmbiguousShapes() {
        assertThrows(IllegalArgumentException.class, () -> new ProposalFields.RequirementCoverage(
                "requirement", ProposalFields.RequirementStatus.SATISFIED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProposalFields.RequirementCoverage(
                "requirement", ProposalFields.RequirementStatus.PLANNED, List.of("fact")));
        assertThrows(IllegalArgumentException.class, () -> new ProposalFields.AuthorityAssessment(
                ProposalFields.AssessmentStatus.BOUND, "artifact", "also missing"));
        assertThrows(IllegalArgumentException.class, () -> new ProposalFields.AuthorityAssessment(
                ProposalFields.AssessmentStatus.NOT_REQUIRED, "artifact", "reason"));
        assertThrows(IllegalArgumentException.class, () -> new ProposalFields.PlanDraft(List.of(
                new ProposalFields.StepDraft("step", 1, "objective", List.of("later"), List.of("done"),
                        List.of("scope"), List.of("deliverable"), false, null))));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorPayload.ToolAction(
                "tool", "{}", "target", "purpose", List.of("output"), "permission",
                List.of(), List.of(), "error", "action", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorPayload.StepBlocked(
                "category", "error", List.of("action"), "no progress", "review",
                List.of(), "question", "format", null));
    }

    @Test
    void gapValidationIsBoundToOneValidationInvocationAndLegalSuccessorKind() {
        GapValidation resolved = new GapValidation("gap",
                List.of(new GapValidation.Check("condition", true, "fact")), GapValidation.Outcome.RESOLVED);
        ProviderRoleOutput resolvedOutput = new ProviderRoleOutput("1", "DIRECT_ROUTE",
                new PlannerPayload.DirectRoute(
                        "reason", "spec", List.of(), List.of(), false, false, false, false, resolved));
        resolvedOutput.validateFor(ChainRole.PLANNER, ChainWorkState.VALIDATING_PENDING_ITEM, "gap");
        assertThrows(IllegalArgumentException.class, () -> resolvedOutput.validateFor(
                ChainRole.PLANNER, ChainWorkState.VALIDATING_PENDING_ITEM, "other-gap"));

        GapValidation stillPending = new GapValidation("gap",
                List.of(new GapValidation.Check("condition", false, "fact")),
                GapValidation.Outcome.STILL_PENDING);
        ProviderRoleOutput blocked = new ProviderRoleOutput("1", "STEP_BLOCKED",
                new ExecutorPayload.StepBlocked("category", "error", List.of("action"), "no progress",
                        "review", List.of("field"), "question", "format", stillPending));
        blocked.validateFor(ChainRole.EXECUTOR, ChainWorkState.VALIDATING_PENDING_ITEM, "gap");
        assertThrows(IllegalArgumentException.class, () -> new ProviderRoleOutput("1", "DIRECT_ROUTE",
                new PlannerPayload.DirectRoute(
                        "reason", "spec", List.of(), List.of(), false, false, false, false, stillPending))
                .validateFor(ChainRole.PLANNER, ChainWorkState.VALIDATING_PENDING_ITEM, "gap"));
    }

    @Test
    void directRouteMechanicallyRejectsPersistentBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new PlannerPayload.DirectRoute(
                "reason", "spec", List.of(), List.of(), true, false, false, false, null));
        assertEquals(2, ChainExecutionMode.values().length);
    }

    @Test
    void freezesExactlyFiveControlledTransitionsAndTheirStages() {
        assertEquals(5, ChainTransitionType.values().length);
        for (ChainTransitionType type : ChainTransitionType.values()) {
            for (List<ChainTransitionStage> path : type.paths()) {
                assertEquals(ChainTransitionStage.OPEN, path.get(0));
                assertEquals(ChainTransitionStage.COMPLETE,
                        path.get(path.size() - 1));
            }
        }
        assertEquals(2, ChainTransitionType.FINALIZATION.paths().size());
        assertEquals(List.of(
                        ChainTransitionStage.PUBLISH_COMMITTED_OR_NOT_REQUIRED,
                        ChainTransitionStage.FAILED_CHECK_HANDOFF_COMMITTED),
                ChainTransitionType.FINALIZATION.validNextStages(List.of(
                        ChainTransitionStage.OPEN,
                        ChainTransitionStage.READINESS_VERIFIED,
                        ChainTransitionStage.FINALIZATION_CHECK_COMMITTED)));
        assertTrue(ChainTransitionType.FINALIZATION.accepts(ChainTransitionStage.TASK_OUTCOME_COMMITTED));
        assertTrue(ChainTransitionType.FINALIZATION.accepts(ChainTransitionStage.FAILED_CHECK_HANDOFF_COMMITTED));
        assertFalse(ChainTransitionType.GAP_RESOLUTION.accepts(ChainTransitionStage.READINESS_COMMITTED));
    }

    @Test
    void freezesAppendOnlyAndProjectionStatusVocabularies() {
        assertEquals(List.of("ACCEPTED", "REJECTED", "STALE", "REPLACED_BY_OFFICIAL_RESULT"),
                Arrays.stream(ChainProposalState.values()).map(Enum::name).toList());
        assertEquals(5, ChainPendingItemStatus.values().length);
        assertEquals(4, ChainTaskOutcomeStatus.values().length);
        assertEquals(4, ChainDeliveryStatus.values().length);
        assertEquals(7, ChainStepStatus.values().length);
        assertEquals(11, ChainWorkState.values().length);
    }

    @Test
    void combinedFinalReviewCannotCarryTwoDifferentReviewPayloads() {
        ProposalFields.ReviewCommon first = new ProposalFields.ReviewCommon(
                "scope", List.of("object"), "reason", List.of("fact"), List.of());
        ProposalFields.ReviewCommon second = new ProposalFields.ReviewCommon(
                "other", List.of("object"), "reason", List.of("fact"), List.of());
        ReflectorPayload.AcceptStep acceptance = new ReflectorPayload.AcceptStep(
                first, "candidate-result", List.of(new ProposalFields.RequirementCoverage(
                "requirement", ProposalFields.RequirementStatus.SATISFIED, List.of("fact"))), List.of("artifact"),
                "task-frame", "revision", "step", "candidate", List.of());
        ProposalFields.FinalizationAssessment finalization =
                new ProposalFields.FinalizationAssessment(
                        List.of(new ProposalFields.RequirementCoverage(
                                "requirement", ProposalFields.RequirementStatus.SATISFIED, List.of("fact"))),
                        bound("artifact"), bound("candidate"), bound("validation"), bound("publish"),
                        List.of("fact"), List.of());
        assertThrows(IllegalArgumentException.class, () ->
                new ReflectorPayload.AcceptStepAndReadyToFinalize(
                        second, acceptance, finalization));
    }

    private static List<ChainProposalPayload> allPayloads() {
        ProposalFields.RequirementCoverage coverage =
                new ProposalFields.RequirementCoverage(
                        "requirement", ProposalFields.RequirementStatus.SATISFIED, List.of("fact"));
        ProposalFields.ApplicabilitySuggestion applicability = new ProposalFields.ApplicabilitySuggestion(
                "accepted", ChainApplicability.Outcome.APPLICABLE, "reason", "position");
        ProposalFields.ReviewCommon review = new ProposalFields.ReviewCommon(
                "scope", List.of("object"), "reason", List.of("fact"), List.of());
        ProposalFields.FinalizationAssessment finalization = new ProposalFields.FinalizationAssessment(
                List.of(coverage), bound("artifact"), bound("candidate"), bound("validation"), bound("publish"),
                List.of("fact"), List.of());
        ProposalFields.TaskFrameDraft taskFrame = new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"), List.of(), "version", "tier",
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(), io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        ProposalFields.PlanDraft plan = new ProposalFields.PlanDraft(List.of(new ProposalFields.StepDraft(
                "step-1", 1, "objective", List.of(), List.of("done"), List.of("scope"),
                List.of("deliverable"), false, null)));
        ReflectorPayload.AcceptStep accepted = new ReflectorPayload.AcceptStep(
                review, "candidate-result", List.of(coverage), List.of("artifact"),
                "task-frame", "revision", "step", "candidate", List.of(applicability));
        return List.of(
                new PlannerPayload.DirectRoute(
                        "reason", "spec", List.of(), List.of(), false, false, false, false, null),
                new PlannerPayload.PersistentPlan(taskFrame, List.of(coverage), plan, List.of(), null),
                new PlannerPayload.PlanRevision("trigger", "old", plan, List.of(coverage), List.of(),
                        List.of(), List.of(), List.of(), "task-frame", null),
                new PlannerPayload.NeedUserInput(List.of("field"), "reason", "question", "format",
                        List.of("condition"), ChainRole.PLANNER, ChainRole.PLANNER, "position", null),
                new PlannerPayload.NeedPermission("kind", "scope", "purpose", "alternative", "position", null),
                new PlannerPayload.UserInstructionDisposition("instruction", "class", "continue", false,
                        "position", false, List.of(applicability), List.of(), null),
                new PlannerPayload.PlanningBlocked(
                        "category", List.of("fact"), "reason", "condition", "gap", null),
                new ExecutorPayload.ToolAction("tool", "{}", "target", "purpose", List.of("output"),
                        "permission", List.of(), List.of(), null, null, null, null, null),
                new ExecutorPayload.WorkspaceChange("candidate", List.of("file"), "patch", "reason",
                        List.of("condition"), List.of(), null),
                new ExecutorPayload.StepResult(List.of(coverage), "body", List.of(), null,
                        List.of(), List.of(), List.of(), List.of(), null),
                new ExecutorPayload.StepBlocked("category", "error", List.of("action"), "no progress",
                        "review", List.of(), null, null, null),
                new ReflectorPayload.ContinueStep(review, List.of("condition"), List.of("gap"), "scope"),
                accepted,
                new ReflectorPayload.AcceptStepAndReadyToFinalize(review, accepted, finalization),
                new ReflectorPayload.ReplanRequired(review, "reason", List.of(), List.of("constraint")),
                new ReflectorPayload.NeedUserInput(review, List.of("field"), "reason", "question", "format",
                        List.of("condition"), ChainRole.PLANNER, "position"),
                new ReflectorPayload.NeedPermission(review, "kind", "scope", "purpose", "alternative",
                        ChainRole.PLANNER, "NEW_INTAKE"),
                new ReflectorPayload.ReadyToFinalize(review, finalization),
                new ReflectorPayload.TaskFailed(review, finalization, List.of("fact"), List.of("item"), "category"),
                new AnswerPayload.DirectAnswer("route", "spec", "body", List.of()),
                new AnswerPayload.EscalateToPersistent("route", "reason", List.of("tool"), List.of(), false),
                new AnswerPayload.UserQuestion("gap", "body"),
                new AnswerPayload.StatusOrFailure("status", "decision", "outcome", "body"),
                new AnswerPayload.FinalDelivery("outcome", List.of("artifact"), "validation", "publish", "body"),
                new AnswerPayload.DeliveryBlocked("reason", List.of("fact"), "retry"));
    }

    private static ProposalFields.AuthorityAssessment bound(String ref) {
        return new ProposalFields.AuthorityAssessment(
                ProposalFields.AssessmentStatus.BOUND, ref, null);
    }
}
