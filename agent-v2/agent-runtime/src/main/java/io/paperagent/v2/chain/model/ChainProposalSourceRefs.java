package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Explicit 25-kind source-reference schema; never infers authority from field names. */
final class ChainProposalSourceRefs {
    private ChainProposalSourceRefs() {
    }

    static List<String> extract(ChainProposalPayload payload) {
        Objects.requireNonNull(payload, "payload");
        TreeSet<String> refs = new TreeSet<>();
        if (payload instanceof PlannerPayload.DirectRoute value) {
            addAll(refs, value.answerRequiredRefs());
        } else if (payload instanceof PlannerPayload.PersistentPlan value) {
            taskFrame(refs, value.taskFrameDraft());
            coverage(refs, value.requirementCoverage());
            applicability(refs, value.predecessorApplicability());
        } else if (payload instanceof PlannerPayload.PlanRevision value) {
            add(refs, value.triggerDecisionOrGapRef());
            add(refs, value.oldRevisionRef());
            coverage(refs, value.requirementCoverage());
            applicability(refs, value.applicability());
            add(refs, value.taskFrameRef());
        } else if (payload instanceof PlannerPayload.NeedUserInput) {
            // Only gapValidation, collected once below, carries source authority.
        } else if (payload instanceof PlannerPayload.NeedPermission) {
            // Only gapValidation, collected once below, carries source authority.
        } else if (payload instanceof PlannerPayload.UserInstructionDisposition value) {
            add(refs, value.instructionRef());
            applicability(refs, value.applicability());
        } else if (payload instanceof PlannerPayload.PlanningBlocked value) {
            addAll(refs, value.knownFactRefs());
        } else if (payload instanceof ExecutorPayload.ToolAction value) {
            add(refs, value.toolId());
            add(refs, value.requiredPermission());
            add(refs, value.priorErrorRef());
            add(refs, value.priorActionRef());
        } else if (payload instanceof ExecutorPayload.WorkspaceChange value) {
            add(refs, value.baseCandidateRef());
        } else if (payload instanceof ExecutorPayload.StepResult value) {
            coverage(refs, value.completionConditionStatus());
            addAll(refs, value.artifactRefs());
            add(refs, value.candidateRef());
            addAll(refs, value.receiptRefs());
            value.validationSources().forEach(source ->
                    add(refs, source.receiptRef()));
            addAll(refs, value.validationRefs());
            addAll(refs, value.evidenceRefs());
        } else if (payload instanceof ExecutorPayload.StepBlocked value) {
            add(refs, value.errorRef());
            addAll(refs, value.attemptedActionOrRepairRefs());
        } else if (payload instanceof ReflectorPayload.ContinueStep value) {
            review(refs, value.review());
            addAll(refs, value.gapOrErrorRefs());
        } else if (payload instanceof ReflectorPayload.AcceptStep value) {
            acceptedStep(refs, value);
        } else if (payload instanceof ReflectorPayload.AcceptStepAndReadyToFinalize value) {
            review(refs, value.review());
            acceptedStep(refs, value.acceptance());
            finalization(refs, value.finalization());
        } else if (payload instanceof ReflectorPayload.ReplanRequired value) {
            review(refs, value.review());
            add(refs, value.replanReasonOrGapRef());
            applicability(refs, value.reuseSuggestions());
        } else if (payload instanceof ReflectorPayload.NeedUserInput value) {
            review(refs, value.review());
        } else if (payload instanceof ReflectorPayload.NeedPermission value) {
            review(refs, value.review());
        } else if (payload instanceof ReflectorPayload.ReadyToFinalize value) {
            review(refs, value.review());
            finalization(refs, value.finalization());
        } else if (payload instanceof ReflectorPayload.TaskFailed value) {
            review(refs, value.review());
            finalization(refs, value.finalization());
            addAll(refs, value.failureFactRefs());
        } else if (payload instanceof AnswerPayload.DirectAnswer value) {
            add(refs, value.routeDecisionRef());
            addAll(refs, value.factRefs());
        } else if (payload instanceof AnswerPayload.EscalateToPersistent value) {
            add(refs, value.directRouteDecisionRef());
            addAll(refs, value.requiredTools());
            addAll(refs, value.requiredProjectEvidence());
        } else if (payload instanceof AnswerPayload.UserQuestion value) {
            add(refs, value.gapId());
        } else if (payload instanceof AnswerPayload.StatusOrFailure value) {
            add(refs, value.taskOrStepStatusRef());
            add(refs, value.latestDecisionRef());
            add(refs, value.blockerOrTaskOutcomeRef());
        } else if (payload instanceof AnswerPayload.FinalDelivery value) {
            add(refs, value.taskOutcomeRef());
            addAll(refs, value.artifactAndCandidateRefs());
            add(refs, value.validationRef());
            add(refs, value.publishRef());
        } else if (payload instanceof AnswerPayload.DeliveryBlocked value) {
            addAll(refs, value.missingOrConflictingFactRefs());
        } else {
            throw new IllegalArgumentException(
                    "unsupported proposal payload type " + payload.getClass().getName());
        }
        gap(refs, payload.gapValidation());
        return List.copyOf(refs);
    }

    private static void taskFrame(TreeSet<String> refs, ProposalFields.TaskFrameDraft frame) {
        addAll(refs, frame.objects());
        add(refs, frame.projectVersion());
        add(refs, frame.permissionTier());
    }

    private static void acceptedStep(TreeSet<String> refs, ReflectorPayload.AcceptStep value) {
        review(refs, value.review());
        add(refs, value.candidateResultId());
        coverage(refs, value.conditionJudgements());
        addAll(refs, value.artifactReceiptCandidateValidationEvidenceRefs());
        add(refs, value.taskFrameRef());
        add(refs, value.planRevisionRef());
        add(refs, value.stepRef());
        add(refs, value.candidateRef());
        applicability(refs, value.affectedAcceptedResults());
    }

    private static void review(TreeSet<String> refs, ProposalFields.ReviewCommon review) {
        addAll(refs, review.reviewedObjectRefs());
        addAll(refs, review.directFactRefs());
    }

    private static void finalization(
            TreeSet<String> refs, ProposalFields.FinalizationAssessment finalization) {
        coverage(refs, finalization.requirementCoverage());
        assessment(refs, finalization.finalArtifactAssessment());
        assessment(refs, finalization.finalCandidateAssessment());
        assessment(refs, finalization.validationAssessment());
        assessment(refs, finalization.publishRequirementAssessment());
    }

    private static void assessment(
            TreeSet<String> refs, ProposalFields.AuthorityAssessment assessment) {
        if (assessment.status() == ProposalFields.AssessmentStatus.BOUND) {
            add(refs, assessment.authorityRef());
        }
    }

    private static void coverage(
            TreeSet<String> refs, List<ProposalFields.RequirementCoverage> coverage) {
        coverage.forEach(value -> addAll(refs, value.factRefs()));
    }

    private static void applicability(
            TreeSet<String> refs, List<ProposalFields.ApplicabilitySuggestion> values) {
        values.forEach(value -> add(refs, value.acceptedResultId()));
    }

    private static void gap(TreeSet<String> refs, GapValidation gap) {
        if (gap == null) {
            return;
        }
        add(refs, gap.gapId());
        gap.checks().forEach(check -> add(refs, check.factRef()));
    }

    private static void addAll(TreeSet<String> refs, Collection<String> values) {
        values.forEach(value -> add(refs, value));
    }

    private static void add(TreeSet<String> refs, String value) {
        if (value != null) {
            refs.add(value);
        }
    }
}
