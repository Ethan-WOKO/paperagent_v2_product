package com.yanban.api.agent.v2.chain.validation;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.validation.ChainValidationAuthorityPort;
import io.paperagent.v2.chain.validation.ChainValidationBundleRuntime;
import io.paperagent.v2.chain.validation.ChainValidationRuntime;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainValidationBundleAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-09T07:00:00Z");
    private static final String H1 = "1".repeat(64);
    private static final String H2 = "2".repeat(64);
    private static final String H3 = "3".repeat(64);
    private static final String H4 = "4".repeat(64);
    private static final String H5 = "5".repeat(64);

    @Test
    void buildsCrossStepCandidateActionAndActionOnlySourcesAndReplays() {
        Fixture fixture = Fixture.valid();
        var first = assertInstanceOf(ChainValidationBundleRuntime.Committed.class,
                fixture.authority().build(fixture.command()));
        var replay = assertInstanceOf(ChainValidationBundleRuntime.Committed.class,
                fixture.authority().build(fixture.command()));

        assertEquals(List.of("step-1", "step-2"), first.sets().stream()
                .map(ChainPersistenceRecords.ValidationBundleSetRecord::stepId)
                .toList());
        assertTrue(replay.replayed());
        assertEquals(first.bundle(), replay.bundle());
    }

    @Test
    void explicitNoRequirementsDoesNotReadStepResultsOrCreateBundle() {
        Fixture fixture = Fixture.notRequired();
        assertInstanceOf(ChainValidationBundleRuntime.NotRequired.class,
                fixture.authority().build(fixture.command()));
        assertEquals(0, fixture.bundles.appendCount);

        fixture.requirements = TaskRequirements.legacyUnspecified();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.authority().build(fixture.command()));
    }

    @Test
    void candidateOnlyValidationStepBuildsAPlanBundle() {
        Fixture fixture = Fixture.candidateOnly();

        var committed = assertInstanceOf(
                ChainValidationBundleRuntime.Committed.class,
                fixture.authority().build(fixture.command()));

        assertEquals(List.of("step-1"), committed.sets().stream()
                .map(ChainPersistenceRecords.ValidationBundleSetRecord::stepId)
                .toList());
    }

    @Test
    void rejectsMissingMultipleUnacceptedAndWrongBoundaryResults() {
        Fixture missing = Fixture.valid();
        missing.workflow.candidates.remove(0);
        assertRejected(missing);

        Fixture multiple = Fixture.valid();
        multiple.workflow.candidates.add(multiple.workflow.candidates.get(0));
        assertRejected(multiple);

        Fixture unaccepted = Fixture.valid();
        unaccepted.workflow.accepted.remove(0);
        assertRejected(unaccepted);

        for (String drift : List.of("instruction", "plan", "step", "activation")) {
            Fixture fixture = Fixture.valid();
            var value = fixture.workflow.candidates.get(0);
            fixture.workflow.candidates.set(0, copyCandidate(value,
                    drift.equals("instruction") ? "instruction-wrong"
                            : value.instructionId(),
                    drift.equals("plan") ? "revision-wrong"
                            : value.planRevisionId(),
                    drift.equals("step") ? "step-wrong" : value.stepId(),
                    drift.equals("activation") ? "activation-wrong"
                            : value.activationEventId(),
                    value.receiptRefs()));
            assertRejected(fixture);
        }
    }

    @Test
    void rejectsMissingOrAmbiguousValidationAuthoritiesAndWorkspaceDrift() {
        Fixture missingSet = Fixture.valid();
        missingSet.validations.sets.remove(
                missingSet.workflow.candidates.get(0).validationId());
        assertRejected(missingSet);

        Fixture missingItems = Fixture.valid();
        missingItems.validations.candidates.clear();
        assertRejected(missingItems);

        Fixture ambiguousItems = Fixture.valid();
        String firstValidation = ambiguousItems.workflow.candidates.get(0)
                .validationId();
        var items = ambiguousItems.validations.candidates.get(firstValidation);
        items.add(items.get(0));
        assertRejected(ambiguousItems);

        Fixture missingEvent = Fixture.valid();
        missingEvent.foundation.events.remove(0);
        assertRejected(missingEvent);

        Fixture ambiguousEvent = Fixture.valid();
        ambiguousEvent.foundation.events.add(
                ambiguousEvent.foundation.events.get(0));
        assertRejected(ambiguousEvent);

        Fixture badWorkspace = Fixture.valid();
        var workspace = badWorkspace.workflow.workspaces.get(0);
        badWorkspace.workflow.workspaces.set(0,
                new ChainPersistenceRecords.WorkspaceCandidateRecord(
                        workspace.workspaceCandidateId(), workspace.taskId(),
                        workspace.eventId(), workspace.actionId(),
                        "workspace-wrong", workspace.baseProjectVersion(),
                        workspace.artifactId(), workspace.candidateFingerprint(),
                        workspace.diffDigest(), workspace.versionFenceSha256(),
                        NOW));
        assertRejected(badWorkspace);
    }

    @Test
    void rejectsCorruptReceiptsAcceptanceIdentityReviewFactsAndStages() {
        Fixture receipts = Fixture.valid();
        var candidate = receipts.workflow.candidates.get(0);
        var corrupt = new ChainPersistenceRecords.CanonicalJson(
                1, H5, candidate.receiptRefs().json());
        receipts.workflow.candidates.set(0, copyCandidate(candidate,
                candidate.instructionId(), candidate.planRevisionId(),
                candidate.stepId(), candidate.activationEventId(), corrupt));
        assertRejected(receipts);

        Fixture accepted = Fixture.valid();
        var oldAccepted = accepted.workflow.accepted.get(0);
        accepted.workflow.accepted.set(0,
                new ChainPersistenceRecords.AcceptedResultRecord(
                        oldAccepted.acceptedResultId(), oldAccepted.taskId(),
                        oldAccepted.eventId(), oldAccepted.candidateResultId(),
                        oldAccepted.reviewDecisionId(),
                        oldAccepted.transitionId(), oldAccepted.contentId(),
                        H5, NOW));
        assertRejected(accepted);

        Fixture review = Fixture.valid();
        var oldReview = review.workflow.reviews.get(0);
        review.workflow.reviews.set(0,
                new ChainPersistenceRecords.ReviewDecisionRecord(
                        oldReview.reviewDecisionId(), oldReview.taskId(),
                        oldReview.eventId(), oldReview.proposalId(),
                        oldReview.reviewObjectType(), oldReview.reviewObjectId(),
                        oldReview.decisionKind(), oldReview.reason(),
                        new ChainPersistenceRecords.CanonicalJson(
                                1, H5, oldReview.factRefs().json()),
                        oldReview.versionFenceSha256(), NOW));
        assertRejected(review);

        Fixture proposalBinding = Fixture.valid();
        var formalReview = proposalBinding.workflow.reviews.get(0);
        proposalBinding.bindProposal(formalReview.proposalId(),
                formalReview.decisionKind(), "REVIEW_DECISION",
                "review-other");
        assertRejected(proposalBinding);

        Fixture stages = Fixture.valid();
        stages.workflow.stages.values().iterator().next().remove(5);
        assertRejected(stages);

        Fixture wrongStageRef = Fixture.valid();
        var stageList = wrongStageRef.workflow.stages.values()
                .iterator().next();
        var acceptedStage = stageList.get(1);
        stageList.set(1, new ChainPersistenceRecords.TransitionStageRecord(
                acceptedStage.transitionId(), acceptedStage.stageCode(),
                acceptedStage.taskId(), acceptedStage.eventId(),
                acceptedStage.stageOrdinal(), null, null,
                "ACCEPTED_RESULT", "accepted-other", NOW));
        assertRejected(wrongStageRef);

        Fixture superseded = Fixture.valid();
        var completed = superseded.steps.events.get(1).command();
        superseded.steps.events.add(new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        "superseded-1", completed.taskId(),
                        completed.planRevisionId(), completed.stepId(),
                        completed.activationEventId(),
                        ChainStepAuthorityPort.StepEventKind.SUPERSEDED_BY_REPLAN,
                        completed.sourceDecisionId(), completed.transitionId(),
                        NOW), 99));
        assertRejected(superseded);

        Fixture missingActivation = Fixture.valid();
        missingActivation.steps.events.removeIf(value ->
                value.command().stepId().equals("step-1")
                        && value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED);
        assertRejected(missingActivation);

        Fixture duplicateActivation = Fixture.valid();
        duplicateActivation.steps.events.add(
                duplicateActivation.steps.events.get(0));
        assertRejected(duplicateActivation);

        Fixture finalSuccessor = Fixture.valid();
        finalSuccessor.makeFinalStepReadiness(false);
        assertInstanceOf(ChainValidationBundleRuntime.Committed.class,
                finalSuccessor.authority().build(finalSuccessor.command()));
        var finalReplay = assertInstanceOf(
                ChainValidationBundleRuntime.Committed.class,
                finalSuccessor.authority().build(finalSuccessor.command()));
        assertTrue(finalReplay.replayed());

        Fixture finalPredecessor = Fixture.valid();
        finalPredecessor.makeFinalStepReadiness(true);
        assertRejected(finalPredecessor);
    }

    @Test
    void finalReadinessBuildsOnlyAtExactCompletedStepPrefix() {
        Fixture missingMiddle = Fixture.valid();
        missingMiddle.makeFinalStepReadiness(false);
        missingMiddle.finalStages().remove(2);
        assertRejected(missingMiddle);

        Fixture duplicate = Fixture.valid();
        duplicate.makeFinalStepReadiness(false);
        duplicate.finalStages().add(2, duplicate.finalStages().get(1));
        assertRejected(duplicate);

        Fixture outOfOrder = Fixture.valid();
        outOfOrder.makeFinalStepReadiness(false);
        var stages = outOfOrder.finalStages();
        var accepted = stages.get(1);
        var applicability = stages.get(2);
        stages.set(1, copyStage(accepted, 2));
        stages.set(2, copyStage(applicability, 1));
        assertRejected(outOfOrder);

        Fixture wrongCompletedRef = Fixture.valid();
        wrongCompletedRef.makeFinalStepReadiness(false);
        var completed = wrongCompletedRef.finalStages().get(3);
        wrongCompletedRef.finalStages().set(3,
                new ChainPersistenceRecords.TransitionStageRecord(
                        completed.transitionId(), completed.stageCode(),
                        completed.taskId(), completed.eventId(),
                        completed.stageOrdinal(), null, null,
                        "STEP_EVENT", "completed-other", NOW));
        assertRejected(wrongCompletedRef);

        Fixture wrongApplicabilityRef = Fixture.valid();
        wrongApplicabilityRef.makeFinalStepReadiness(false);
        var finalApplicability = wrongApplicabilityRef.finalStages().get(2);
        wrongApplicabilityRef.finalStages().set(2,
                new ChainPersistenceRecords.TransitionStageRecord(
                        finalApplicability.transitionId(),
                        finalApplicability.stageCode(),
                        finalApplicability.taskId(), finalApplicability.eventId(),
                        finalApplicability.stageOrdinal(), null, null,
                        "RESULT_APPLICABILITY", "applicability-other", NOW));
        assertRejected(wrongApplicabilityRef);

        Fixture tooEarly = Fixture.valid();
        tooEarly.makeFinalStepReadiness(false);
        tooEarly.finalStages().subList(2,
                tooEarly.finalStages().size()).clear();
        assertRejected(tooEarly);

        Fixture advanced = Fixture.valid();
        advanced.makeFinalStepReadiness(false);
        var prefix = advanced.finalStages();
        String transitionId = prefix.get(0).transitionId();
        prefix.add(new ChainPersistenceRecords.TransitionStageRecord(
                transitionId, ChainTransitionStage.READINESS_COMMITTED,
                "task-1", "final-stage-4", 4, null, null,
                "FINALIZATION_READINESS", "readiness-1", NOW));
        assertRejected(advanced);
    }

    @Test
    void frozenRequirementsAuthorityDriftIsRejected() {
        Fixture fixture = Fixture.valid();
        fixture.requirementsFailure = true;
        assertThrows(ProductChainValidationBundleAuthority
                        .ProductChainValidationBundleException.class,
                () -> fixture.authority().build(fixture.command()));

        Fixture revisionDrift = Fixture.valid();
        var original = revisionDrift.revision;
        var first = original.steps().get(0);
        var changed = new PlanStep(first.id(), first.intent(),
                first.expectedOutcome(), first.dependencies(),
                List.of("changed condition", "action one verified"),
                first.executionHints(), first.constraints(),
                first.mayChangeCandidate(), null,
                first.validationRequirementIds());
        var supplied = new PlanRevision(original.id(), original.taskFrameId(),
                original.number(), original.parentRevisionId(),
                original.reason(), original.createdAt(),
                List.of(changed, original.steps().get(1)), Map.of());
        assertThrows(ProductChainValidationBundleAuthority
                        .ProductChainValidationBundleException.class,
                () -> revisionDrift.authority().build(
                        revisionDrift.command(supplied)));

        var changedIds = new PlanStep(first.id(), first.intent(),
                first.expectedOutcome(), first.dependencies(),
                first.completionCriteria(), first.executionHints(),
                first.constraints(), first.mayChangeCandidate(), null,
                List.of("other-required", "action-required-1"));
        var suppliedIds = new PlanRevision(
                original.id(), original.taskFrameId(), original.number(),
                original.parentRevisionId(), original.reason(),
                original.createdAt(),
                List.of(changedIds, original.steps().get(1)), Map.of());
        assertThrows(ProductChainValidationBundleAuthority
                        .ProductChainValidationBundleException.class,
                () -> revisionDrift.authority().build(
                        revisionDrift.command(suppliedIds)));
    }

    private static void assertRejected(Fixture fixture) {
        assertThrows(RuntimeException.class,
                () -> fixture.authority().build(fixture.command()));
    }

    private static ChainPersistenceRecords.TransitionStageRecord copyStage(
            ChainPersistenceRecords.TransitionStageRecord value,
            int ordinal) {
        return new ChainPersistenceRecords.TransitionStageRecord(
                value.transitionId(), value.stageCode(), value.taskId(),
                value.eventId(), ordinal, value.predecessorAuthorityType(),
                value.predecessorAuthorityRef(), value.successorAuthorityType(),
                value.successorAuthorityRef(), value.committedAt());
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord
            copyCandidate(
                    ChainPersistenceRecords.CandidateStepResultRecord value,
                    String instruction, String revision, String step,
                    String activation,
                    ChainPersistenceRecords.CanonicalJson receiptRefs) {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                value.candidateResultId(), value.taskId(), value.eventId(),
                value.proposalId(), value.contentId(), instruction,
                value.taskFrameId(), value.planId(), revision,
                value.planRevisionNumber(), step, activation,
                value.artifactId(), value.candidateFingerprint(),
                value.diffDigest(), receiptRefs, value.validationId(),
                value.validationRequestDigest(),
                value.validationReceiptDigest(), value.evidenceRefs(),
                value.versionFenceSha256(), NOW);
    }

    private static final class Fixture {
        private final FakeFoundation foundation = new FakeFoundation();
        private final ChainModelRepository models = mock(
                ChainModelRepository.class);
        private final FakeWorkflow workflow = new FakeWorkflow();
        private final FakeSteps steps = new FakeSteps();
        private final FakeValidationRepository validations =
                new FakeValidationRepository();
        private final FakeBundleRepository bundles = new FakeBundleRepository();
        private TaskRequirements requirements;
        private PlanRevision revision;
        private boolean requirementsFailure;

        static Fixture valid() {
            Fixture fixture = new Fixture();
            var candidate = new ValidationRequirement(
                    "candidate-required", ValidationSubject.CANDIDATE,
                    "candidate verified");
            var action1 = new ValidationRequirement(
                    "action-required-1", ValidationSubject.ACTION_RECEIPT,
                    "action one verified");
            var action2 = new ValidationRequirement(
                    "action-required-2", ValidationSubject.ACTION_RECEIPT,
                    "action two verified");
            fixture.requirements = TaskRequirements.explicit(
                    List.of(candidate, action1, action2),
                    PublishRequirement.NOT_REQUIRED);
            fixture.revision = revision(List.of(
                    step("step-1", Set.of(), List.of(candidate, action1)),
                    step("step-2", Set.of(new PlanStepId("step-1")),
                            List.of(action2))));
            fixture.steps.plan = snapshot(fixture.revision);
            fixture.addStep("step-1", "activation-1",
                    List.of(candidate, action1), true, 1);
            fixture.addStep("step-2", "activation-2",
                    List.of(action2), false, 20);
            return fixture;
        }

        static Fixture notRequired() {
            Fixture fixture = new Fixture();
            fixture.requirements = TaskRequirements.explicit(
                    List.of(), PublishRequirement.NOT_REQUIRED);
            fixture.revision = revision(List.of(step("step-1", Set.of(),
                    List.of())));
            fixture.steps.plan = snapshot(fixture.revision);
            return fixture;
        }

        static Fixture candidateOnly() {
            Fixture fixture = new Fixture();
            var candidate = new ValidationRequirement(
                    "candidate-required", ValidationSubject.CANDIDATE,
                    "candidate verified");
            fixture.requirements = TaskRequirements.explicit(
                    List.of(candidate), PublishRequirement.NOT_REQUIRED);
            fixture.revision = revision(List.of(
                    step("step-1", Set.of(), List.of(candidate))));
            fixture.steps.plan = snapshot(fixture.revision);
            fixture.addStep("step-1", "activation-1",
                    List.of(candidate), true, 1);
            return fixture;
        }

        ProductChainValidationBundleAuthority authority() {
            ProductChainValidationBundleAuthority.RequirementsAuthority source =
                    (planId, frameId) -> {
                        if (requirementsFailure
                                || !planId.equals("plan-1")
                                || !frameId.equals("frame-1")) {
                            throw new ProductChainValidationBundleAuthority
                                    .ProductChainValidationBundleException(
                                    "TASK_FRAME_DRIFT");
                        }
                        return requirements;
                    };
            return new ProductChainValidationBundleAuthority(
                    foundation, models, workflow, steps, validations, source,
                    (taskId, revisionId) -> Optional.of(revision),
                    new ChainValidationBundleRuntime(bundles));
        }

        ProductChainValidationBundleAuthority.BuildCommand command() {
            return command(revision);
        }

        ProductChainValidationBundleAuthority.BuildCommand command(
                PlanRevision suppliedRevision) {
            var task = new ChainPersistenceRecords.TaskRecord(
                    "task-1", "command-1", "instruction-1", null,
                    1, 2, 3, null, "request-1", H1,
                    null, null, 100, NOW);
            var binding = new ChainPersistenceRecords.PlanBindingRecord(
                    "binding-1", "task-1", "binding-event",
                    "instruction-1", "route-1", "frame-1", "plan-1",
                    "revision-1", 1, "PLAN_BOOTSTRAP", "plan-1", H1,
                    null, NOW);
            return new ProductChainValidationBundleAuthority.BuildCommand(
                    task, binding, suppliedRevision,
                    "step-" + suppliedRevision.steps().size(),
                    "bundle-key", NOW);
        }

        private void addStep(
                String stepId, String activation,
                List<ValidationRequirement> requirements,
                boolean candidate, long sequence) {
            CapturingValidationRepository capture =
                    new CapturingValidationRepository();
            var committed = new ChainValidationRuntime(
                    validationAuthority(), capture)
                    .commit(new ChainValidationRuntime.CommitCommand(
                            new ChainValidationRuntime.Scope(
                                    "task-1", "frame-1", "plan-1",
                                    "revision-1", 1, stepId, activation,
                                    "validation-key-" + stepId, NOW),
                            requirements, requirements.stream().map(value ->
                            new ProposalFields.ValidationSource(
                                    value.requirementId(), receipt(value)))
                            .toList()));
            validations.put(committed);
            foundation.events.add(capture.event);
            if (candidate) {
                workflow.workspaces.add(
                        new ChainPersistenceRecords.WorkspaceCandidateRecord(
                                "workspace-candidate-1", "task-1",
                                "workspace-event", "candidate-action",
                                "workspace-1", H4, 101, H3, H5, H1, NOW));
            }
            List<String> refs = requirements.stream().map(Fixture::receipt)
                    .sorted().toList();
            String json = refs.stream().map(value -> "\"" + value + "\"")
                    .reduce((a, b) -> a + "," + b)
                    .map(value -> "[" + value + "]").orElse("[]");
            var result = new ChainPersistenceRecords.CandidateStepResultRecord(
                    "result-" + stepId, "task-1", "result-event-" + stepId,
                    "proposal-" + stepId, "content-" + stepId,
                    "instruction-1", "frame-1", "plan-1", "revision-1",
                    1, stepId, activation, candidate ? 101L : null,
                    candidate ? H3 : null, candidate ? H5 : null,
                    canonical(json), committed.validation().validationId(),
                    committed.validation().requestDigest(),
                    committed.validation().receiptSetDigest(),
                    canonical("[]"), H1, NOW);
            workflow.candidates.add(result);
            bindProposal(result.proposalId(),
                    ChainProposalKind.EXECUTOR_STEP_RESULT,
                    "CANDIDATE_STEP_RESULT", result.candidateResultId());
            String reviewId = "review-" + stepId;
            String reviewProposalId = "review-proposal-" + stepId;
            var review = new ChainPersistenceRecords.ReviewDecisionRecord(
                    reviewId, "task-1", "review-event-" + stepId,
                    reviewProposalId, "CANDIDATE_STEP_RESULT",
                    result.candidateResultId(),
                    ChainProposalKind.REFLECTOR_ACCEPT_STEP, "accepted",
                    canonical("[]"), H1, NOW);
            workflow.reviews.add(review);
            bindProposal(reviewProposalId, review.decisionKind(),
                    "REVIEW_DECISION", reviewId);
            String acceptedHash = sha256(result.candidateResultId() + "\0"
                    + reviewId + "\0" + result.contentId());
            String transitionId = new ChainIdentity.Transition(
                    ChainTransitionType.ACCEPT_STEP, "task-1", reviewId,
                    acceptedHash).transitionId();
            workflow.transitions.put(transitionId,
                    new ChainPersistenceRecords.TransitionRecord(
                            transitionId, "task-1",
                            "transition-event-" + stepId,
                            ChainTransitionType.ACCEPT_STEP, reviewId,
                            acceptedHash, NOW));
            workflow.accepted.add(
                    new ChainPersistenceRecords.AcceptedResultRecord(
                            "accepted-" + stepId, "task-1",
                            "accepted-event-" + stepId,
                            result.candidateResultId(), reviewId, transitionId,
                            result.contentId(), acceptedHash, NOW));
            List<ChainPersistenceRecords.TransitionStageRecord> stages =
                    new ArrayList<>();
            var path = ChainTransitionType.ACCEPT_STEP.paths().get(0);
            for (int index = 0; index < path.size(); index++) {
                String authorityType = index == 1 ? "ACCEPTED_RESULT"
                        : index == 3 ? "STEP_EVENT" : null;
                String authorityRef = index == 1 ? "accepted-" + stepId
                        : index == 3 ? "completed-" + stepId : null;
                stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                        transitionId, path.get(index), "task-1",
                        "stage-" + stepId + "-" + index, index,
                        null, null, authorityType, authorityRef, NOW));
            }
            workflow.stages.put(transitionId, stages);
            steps.events.add(new ChainStepAuthorityPort.StepEvent(
                    new ChainStepAuthorityPort.StepEventCommand(
                            activation, "task-1", "revision-1", stepId,
                            activation, ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                            reviewId, transitionId, NOW), sequence));
            steps.events.add(new ChainStepAuthorityPort.StepEvent(
                    new ChainStepAuthorityPort.StepEventCommand(
                            "completed-" + stepId, "task-1", "revision-1",
                            stepId, activation,
                            ChainStepAuthorityPort.StepEventKind.COMPLETED,
                    reviewId, transitionId, NOW), sequence + 1));
        }

        private void makeFinalStepReadiness(boolean predecessorAuthority) {
            String stepId = "step-2";
            var candidate = workflow.candidates.stream()
                    .filter(value -> value.stepId().equals(stepId))
                    .findFirst().orElseThrow();
            int reviewIndex = indexOfReview("review-" + stepId);
            var oldReview = workflow.reviews.get(reviewIndex);
            workflow.reviews.set(reviewIndex,
                    new ChainPersistenceRecords.ReviewDecisionRecord(
                            oldReview.reviewDecisionId(), oldReview.taskId(),
                            oldReview.eventId(), oldReview.proposalId(),
                            oldReview.reviewObjectType(),
                            oldReview.reviewObjectId(),
                            ChainProposalKind
                                    .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE,
                            oldReview.reason(), oldReview.factRefs(),
                            oldReview.versionFenceSha256(), NOW));
            bindProposal(oldReview.proposalId(),
                    ChainProposalKind
                            .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE,
                    "REVIEW_DECISION", oldReview.reviewDecisionId());
            int acceptedIndex = indexOfAccepted("accepted-" + stepId);
            var oldAccepted = workflow.accepted.get(acceptedIndex);
            String newTransitionId = new ChainIdentity.Transition(
                    ChainTransitionType.FINAL_STEP_READINESS, "task-1",
                    oldReview.reviewDecisionId(),
                    oldAccepted.acceptedIdentitySha256()).transitionId();
            workflow.accepted.set(acceptedIndex,
                    new ChainPersistenceRecords.AcceptedResultRecord(
                            oldAccepted.acceptedResultId(), oldAccepted.taskId(),
                            oldAccepted.eventId(),
                            oldAccepted.candidateResultId(),
                            oldAccepted.reviewDecisionId(), newTransitionId,
                            oldAccepted.contentId(),
                            oldAccepted.acceptedIdentitySha256(), NOW));
            workflow.transitions.remove(oldAccepted.transitionId());
            workflow.transitions.put(newTransitionId,
                    new ChainPersistenceRecords.TransitionRecord(
                            newTransitionId, "task-1",
                            "transition-event-final",
                            ChainTransitionType.FINAL_STEP_READINESS,
                            oldReview.reviewDecisionId(),
                            oldAccepted.acceptedIdentitySha256(), NOW));
            workflow.stages.remove(oldAccepted.transitionId());
            List<ChainPersistenceRecords.TransitionStageRecord> finalStages =
                    new ArrayList<>();
            var path = ChainTransitionType.FINAL_STEP_READINESS.paths().get(0);
            for (int index = 0; index < 4; index++) {
                String type = index == 1 ? "ACCEPTED_RESULT"
                        : index == 3 ? "STEP_EVENT" : null;
                String ref = index == 1 ? oldAccepted.acceptedResultId()
                        : index == 3 ? "completed-" + stepId : null;
                finalStages.add(new ChainPersistenceRecords
                        .TransitionStageRecord(
                        newTransitionId, path.get(index), "task-1",
                        "final-stage-" + index, index,
                        predecessorAuthority ? type : null,
                        predecessorAuthority ? ref : null,
                        predecessorAuthority ? null : type,
                        predecessorAuthority ? null : ref, NOW));
            }
            workflow.stages.put(newTransitionId, finalStages);
            for (int index = 0; index < steps.events.size(); index++) {
                var event = steps.events.get(index);
                if (!event.command().stepId().equals(stepId)) continue;
                var command = event.command();
                steps.events.set(index, new ChainStepAuthorityPort.StepEvent(
                        new ChainStepAuthorityPort.StepEventCommand(
                                command.eventId(), command.taskId(),
                                command.planRevisionId(), command.stepId(),
                                command.activationEventId(), command.eventKind(),
                                command.sourceDecisionId(), newTransitionId,
                                command.committedAt()), event.authoritySequence()));
            }
        }

        private List<ChainPersistenceRecords.TransitionStageRecord>
                finalStages() {
            return workflow.stages.values().stream()
                    .filter(values -> !values.isEmpty()
                            && values.get(0).stageCode()
                            == ChainTransitionStage.OPEN
                            && workflow.transitions.get(
                            values.get(0).transitionId()).transitionType()
                            == ChainTransitionType.FINAL_STEP_READINESS)
                    .findFirst().orElseThrow();
        }

        private int indexOfReview(String id) {
            for (int index = 0; index < workflow.reviews.size(); index++) {
                if (workflow.reviews.get(index).reviewDecisionId().equals(id))
                    return index;
            }
            throw new IllegalStateException();
        }

        private int indexOfAccepted(String id) {
            for (int index = 0; index < workflow.accepted.size(); index++) {
                if (workflow.accepted.get(index).acceptedResultId().equals(id))
                    return index;
            }
            throw new IllegalStateException();
        }

        private static String receipt(ValidationRequirement value) {
            return value.subject() == ValidationSubject.CANDIDATE
                    ? "receipt-candidate"
                    : "receipt-" + value.requirementId();
        }

        private void bindProposal(
                String proposalId, ChainProposalKind kind,
                String authorityType, String authorityRef) {
            var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                    proposalId, "task-1", "invocation-" + proposalId, 1,
                    kind.role(), kind, canonical("{}"), canonical("[]"),
                    null, null, NOW);
            when(models.findProposal(proposalId))
                    .thenReturn(Optional.of(proposal));
            when(models.findProposalStateEvents(proposalId)).thenReturn(List.of(
                    new ChainPersistenceRecords.ProposalStateEventRecord(
                            proposalId, 1, "task-1",
                            "accepted-event-" + proposalId,
                            ChainProposalState.ACCEPTED, null, null, NOW),
                    new ChainPersistenceRecords.ProposalStateEventRecord(
                            proposalId, 2, "task-1",
                            "replaced-event-" + proposalId,
                            ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                            authorityType, authorityRef, NOW)));
        }
    }

    private static PlanRevision revision(List<PlanStep> steps) {
        return new PlanRevision(new PlanRevisionId("revision-1"),
                new TaskFrameId("frame-1"), 1, Optional.empty(), "initial",
                NOW, steps, Map.of());
    }

    private static PlanStep step(
            String id, Set<PlanStepId> dependencies,
            List<ValidationRequirement> requirements) {
        List<String> conditions = requirements.isEmpty()
                ? List.of("done") : requirements.stream()
                .map(ValidationRequirement::completionCondition).toList();
        return new PlanStep(new PlanStepId(id), "execute", "done",
                dependencies, conditions,
                new BoundedExecutionHints(1, Duration.ofMinutes(1)),
                List.of(), false, null, requirements.stream()
                .map(ValidationRequirement::requirementId).toList());
    }

    private static ChainStepAuthorityPort.PlanSnapshot snapshot(
            PlanRevision revision) {
        List<ChainStepAuthorityPort.StepDefinition> definitions =
                new ArrayList<>();
        for (int index = 0; index < revision.steps().size(); index++) {
            var step = revision.steps().get(index);
            definitions.add(new ChainStepAuthorityPort.StepDefinition(
                    step.id().value(), index + 1, step.dependencies().stream()
                    .map(PlanStepId::value).collect(
                            java.util.stream.Collectors.toSet())));
        }
        return new ChainStepAuthorityPort.PlanSnapshot(
                "task-1", "frame-1", "plan-1", "revision-1", "NONE",
                "instruction-1", definitions);
    }

    private static ChainValidationAuthorityPort validationAuthority() {
        return new ChainValidationAuthorityPort() {
            public VerifiedCandidate verifyCandidate(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement, String receiptRef) {
                return new VerifiedCandidate("candidate-action",
                        "validation-action", receiptRef, H2, H1,
                        "workspace-candidate-1", "workspace-1", 101,
                        H3, H4);
            }

            public VerifiedActionReceipt verifyActionReceipt(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement, String receiptRef) {
                return new VerifiedActionReceipt(
                        "action-" + requirement.requirementId(), receiptRef,
                        H2, H1);
            }
        };
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class CapturingValidationRepository
            implements ChainValidationRepository {
        private ChainPersistenceRecords.AuthorityEventRecord event;
        public ValidationAppendResult appendValidation(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.ValidationSetRecord> value,
                List<ChainPersistenceRecords.CandidateValidationItemRecord> c,
                List<ChainPersistenceRecords.ActionReceiptValidationItemRecord> a) {
            event = new ChainPersistenceRecords.AuthorityEventRecord(
                    value.event().eventId(), value.event().taskId(), 1,
                    value.event().eventType(), null,
                    value.event().sourceIdentitySha256(), NOW);
            return new ValidationAppendResult(event, value.fact(), c, a, false);
        }
        public Optional<ChainPersistenceRecords.ValidationSetRecord>
                findValidation(String id) { return Optional.empty(); }
        public List<ChainPersistenceRecords.CandidateValidationItemRecord>
                findCandidateItems(String id) { return List.of(); }
        public List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                findActionReceiptItems(String id) { return List.of(); }
    }

    private static final class FakeValidationRepository
            implements ChainValidationRepository {
        private final Map<String, ChainPersistenceRecords.ValidationSetRecord>
                sets = new HashMap<>();
        private final Map<String, List<ChainPersistenceRecords
                .CandidateValidationItemRecord>> candidates = new HashMap<>();
        private final Map<String, List<ChainPersistenceRecords
                .ActionReceiptValidationItemRecord>> actions = new HashMap<>();
        void put(ChainValidationRuntime.CommitResult result) {
            sets.put(result.validation().validationId(), result.validation());
            candidates.put(result.validation().validationId(),
                    new ArrayList<>(result.candidateItems()));
            actions.put(result.validation().validationId(),
                    new ArrayList<>(result.actionReceiptItems()));
        }
        public ValidationAppendResult appendValidation(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.ValidationSetRecord> v,
                List<ChainPersistenceRecords.CandidateValidationItemRecord> c,
                List<ChainPersistenceRecords.ActionReceiptValidationItemRecord> a) {
            throw new UnsupportedOperationException();
        }
        public Optional<ChainPersistenceRecords.ValidationSetRecord>
                findValidation(String id) { return Optional.ofNullable(sets.get(id)); }
        public List<ChainPersistenceRecords.CandidateValidationItemRecord>
                findCandidateItems(String id) { return candidates.getOrDefault(id, List.of()); }
        public List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                findActionReceiptItems(String id) { return actions.getOrDefault(id, List.of()); }
    }

    private static final class FakeFoundation
            implements io.paperagent.v2.chain.ChainFoundationRepository {
        private final List<ChainPersistenceRecords.AuthorityEventRecord> events =
                new ArrayList<>();
        public Optional<ChainPersistenceRecords.CommandRecord> findCommand(
                long u, long s, String id) { return Optional.empty(); }
        public Optional<ChainPersistenceRecords.CommandRecord> findCommand(
                String id) { return Optional.empty(); }
        public Optional<ChainPersistenceRecords.TaskRecord> findTask(String id) {
            return Optional.empty();
        }
        public Optional<ChainPersistenceRecords.InstructionRecord>
                findInstruction(String id) { return Optional.empty(); }
        public List<ChainPersistenceRecords.TaskInstructionBindingRecord>
                findTaskInstructions(String id, long cut) { return List.of(); }
        public List<ChainPersistenceRecords.AuthorityEventRecord>
                findAuthorityEvents(String id, long cut) { return List.copyOf(events); }
        public long highestAuthorityEventSequence(String id) { return 100; }
    }

    private static final class FakeSteps implements ChainStepAuthorityPort {
        private PlanSnapshot plan;
        private final List<StepEvent> events = new ArrayList<>();
        public Optional<PlanSnapshot> findPlan(String task, String revision) {
            return Optional.ofNullable(plan);
        }
        public List<StepEvent> findStepEvents(String task, String revision) {
            return List.copyOf(events);
        }
        public ChainPersistenceRecords.AppendResult<StepEvent> appendStepEvent(
                StepEventCommand command) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeWorkflow implements ChainWorkflowRepository {
        private final List<ChainPersistenceRecords.CandidateStepResultRecord>
                candidates = new ArrayList<>();
        private final List<ChainPersistenceRecords.ReviewDecisionRecord> reviews =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.AcceptedResultRecord> accepted =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                workspaces = new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.TransitionRecord>
                transitions = new HashMap<>();
        private final Map<String, List<ChainPersistenceRecords
                .TransitionStageRecord>> stages = new HashMap<>();
        public Optional<ChainPersistenceRecords.TransitionRecord>
                findTransition(String id) { return Optional.ofNullable(transitions.get(id)); }
        public List<ChainPersistenceRecords.TransitionStageRecord>
                findTransitionStages(String id) { return stages.getOrDefault(id, List.of()); }
        public List<ChainPersistenceRecords.TransitionRecord>
                findIncompleteTransitions(String id) { return List.of(); }
        public List<ChainPersistenceRecords.RouteDecisionRecord>
                findRouteDecisions(String id) { return List.of(); }
        public List<ChainPersistenceRecords.PlanBindingRecord>
                findPlanBindings(String id) { return List.of(); }
        public List<ChainPersistenceRecords.CandidateStepResultRecord>
                findCandidateStepResults(String id) { return List.copyOf(candidates); }
        public List<ChainPersistenceRecords.ReviewDecisionRecord>
                findReviewDecisions(String id) { return List.copyOf(reviews); }
        public List<ChainPersistenceRecords.AcceptedResultRecord>
                findAcceptedResults(String id) { return List.copyOf(accepted); }
        public List<ChainPersistenceRecords.ResultApplicabilityRecord>
                findApplicabilityDecisions(String id) { return List.of(); }
        public List<ChainPersistenceRecords.PendingItemRecord>
                findPendingItems(String id) { return List.of(); }
        public List<ChainPersistenceRecords.PendingItemRecord>
                findOpenPendingItems(String id) { return List.of(); }
        public List<ChainPersistenceRecords.PendingItemEventRecord>
                findPendingItemEvents(String id) { return List.of(); }
        public List<ChainPersistenceRecords.PermissionDecisionRecord>
                findPermissionDecisions(String id) { return List.of(); }
        public List<ChainPersistenceRecords.ActionBindingRecord>
                findActionBindings(String id) { return List.of(); }
        public List<ChainPersistenceRecords.ActionBindingRecord>
                findInFlightActions(String id) { return List.of(); }
        public List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                findWorkspaceCandidates(String id) { return List.copyOf(workspaces); }
    }

    private static final class FakeBundleRepository
            implements ChainValidationBundleRepository {
        private BundleAppendResult stored;
        private int appendCount;
        public BundleAppendResult appendBundle(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.ValidationBundleRecord> value,
                List<ChainPersistenceRecords.ValidationBundleSetRecord> sets) {
            appendCount++;
            if (stored != null) return new BundleAppendResult(
                    stored.event(), stored.bundle(), stored.sets(), true);
            var event = new ChainPersistenceRecords.AuthorityEventRecord(
                    value.event().eventId(), value.event().taskId(), 200,
                    value.event().eventType(), null,
                    value.event().sourceIdentitySha256(), NOW);
            stored = new BundleAppendResult(event, value.fact(), sets, false);
            return stored;
        }
        public Optional<ChainPersistenceRecords.ValidationBundleRecord>
                findBundle(String id) { return Optional.empty(); }
        public List<ChainPersistenceRecords.ValidationBundleSetRecord>
                findBundleSets(String id) { return List.of(); }
    }
}
