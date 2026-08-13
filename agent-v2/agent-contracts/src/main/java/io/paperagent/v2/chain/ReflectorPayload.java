package io.paperagent.v2.chain;

import java.util.List;
import java.util.Objects;

public sealed interface ReflectorPayload extends ChainProposalPayload permits
        ReflectorPayload.ContinueStep, ReflectorPayload.AcceptStep,
        ReflectorPayload.AcceptStepAndReadyToFinalize, ReflectorPayload.ReplanRequired,
        ReflectorPayload.NeedUserInput, ReflectorPayload.NeedPermission,
        ReflectorPayload.ReadyToFinalize, ReflectorPayload.TaskFailed {

    ProposalFields.ReviewCommon review();

    record ContinueStep(
            ProposalFields.ReviewCommon review,
            List<String> unmetConditions,
            List<String> gapOrErrorRefs,
            String allowedContinuationScope) implements ReflectorPayload {
        public ContinueStep {
            review = Objects.requireNonNull(review, "review");
            unmetConditions = ChainValues.nonEmptyCopy(unmetConditions, "unmetConditions");
            gapOrErrorRefs = ChainValues.nonEmptyCopy(gapOrErrorRefs, "gapOrErrorRefs");
            allowedContinuationScope = ChainValues.required(allowedContinuationScope, "allowedContinuationScope");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.REFLECTOR_CONTINUE_STEP; }
    }

    record AcceptStep(
            ProposalFields.ReviewCommon review,
            String candidateResultId,
            List<ProposalFields.RequirementCoverage> conditionJudgements,
            List<String> artifactReceiptCandidateValidationEvidenceRefs,
            String taskFrameRef,
            String planRevisionRef,
            String stepRef,
            String candidateRef,
            List<ProposalFields.ApplicabilitySuggestion> affectedAcceptedResults) implements ReflectorPayload {
        public AcceptStep {
            review = Objects.requireNonNull(review, "review");
            candidateResultId = ChainValues.required(candidateResultId, "candidateResultId");
            conditionJudgements = ChainValues.nonEmptyCopy(conditionJudgements, "conditionJudgements");
            artifactReceiptCandidateValidationEvidenceRefs = ChainValues.nonEmptyCopy(
                    artifactReceiptCandidateValidationEvidenceRefs, "artifactReceiptCandidateValidationEvidenceRefs");
            taskFrameRef = ChainValues.required(taskFrameRef, "taskFrameRef");
            planRevisionRef = ChainValues.required(planRevisionRef, "planRevisionRef");
            stepRef = ChainValues.required(stepRef, "stepRef");
            candidateRef = ChainValues.required(candidateRef, "candidateRef");
            affectedAcceptedResults = ChainValues.copy(affectedAcceptedResults, "affectedAcceptedResults");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.REFLECTOR_ACCEPT_STEP; }
    }

    record AcceptStepAndReadyToFinalize(
            ProposalFields.ReviewCommon review,
            AcceptStep acceptance,
            ProposalFields.FinalizationAssessment finalization) implements ReflectorPayload {
        public AcceptStepAndReadyToFinalize {
            review = Objects.requireNonNull(review, "review");
            acceptance = Objects.requireNonNull(acceptance, "acceptance");
            finalization = Objects.requireNonNull(finalization, "finalization");
            if (!review.equals(acceptance.review())) {
                throw new IllegalArgumentException("combined final-step review must have one common review payload");
            }
        }

        @Override public ChainProposalKind kind() {
            return ChainProposalKind.REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE;
        }
    }

    record ReplanRequired(
            ProposalFields.ReviewCommon review,
            String replanReasonOrGapRef,
            List<ProposalFields.ApplicabilitySuggestion> reuseSuggestions,
            List<String> constraintsToRepair) implements ReflectorPayload {
        public ReplanRequired {
            review = Objects.requireNonNull(review, "review");
            replanReasonOrGapRef = ChainValues.required(replanReasonOrGapRef, "replanReasonOrGapRef");
            reuseSuggestions = ChainValues.copy(reuseSuggestions, "reuseSuggestions");
            constraintsToRepair = ChainValues.nonEmptyCopy(constraintsToRepair, "constraintsToRepair");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.REFLECTOR_REPLAN_REQUIRED; }
    }

    record NeedUserInput(
            ProposalFields.ReviewCommon review,
            List<String> missingFields,
            String userSpecificReason,
            String exactQuestion,
            String expectedFormat,
            List<String> closingConditions,
            ChainRole validationRole,
            String resumePosition) implements ReflectorPayload {
        public NeedUserInput {
            review = Objects.requireNonNull(review, "review");
            missingFields = ChainValues.nonEmptyCopy(missingFields, "missingFields");
            userSpecificReason = ChainValues.required(userSpecificReason, "userSpecificReason");
            exactQuestion = ChainValues.required(exactQuestion, "exactQuestion");
            expectedFormat = ChainValues.required(expectedFormat, "expectedFormat");
            closingConditions = ChainValues.nonEmptyCopy(closingConditions, "closingConditions");
            validationRole = Objects.requireNonNull(validationRole, "validationRole");
            resumePosition = ChainValues.required(resumePosition, "resumePosition");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.REFLECTOR_NEED_USER_INPUT; }
    }

    record NeedPermission(
            ProposalFields.ReviewCommon review,
            String permissionKind,
            String scope,
            String purpose,
            String refusalAlternative,
            ChainRole validationRole,
            String newIntakePosition) implements ReflectorPayload {
        public NeedPermission {
            review = Objects.requireNonNull(review, "review");
            permissionKind = ChainValues.required(permissionKind, "permissionKind");
            scope = ChainValues.required(scope, "scope");
            purpose = ChainValues.required(purpose, "purpose");
            refusalAlternative = ChainValues.required(refusalAlternative, "refusalAlternative");
            validationRole = Objects.requireNonNull(validationRole, "validationRole");
            newIntakePosition = ChainValues.required(newIntakePosition, "newIntakePosition");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.REFLECTOR_NEED_PERMISSION; }
    }

    record ReadyToFinalize(
            ProposalFields.ReviewCommon review,
            ProposalFields.FinalizationAssessment finalization) implements ReflectorPayload {
        public ReadyToFinalize {
            review = Objects.requireNonNull(review, "review");
            finalization = Objects.requireNonNull(finalization, "finalization");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.REFLECTOR_READY_TO_FINALIZE; }
    }

    record TaskFailed(
            ProposalFields.ReviewCommon review,
            ProposalFields.FinalizationAssessment finalization,
            List<String> failureFactRefs,
            List<String> unfinishedOrSkippedItems,
            String failureCategory) implements ReflectorPayload {
        public TaskFailed {
            review = Objects.requireNonNull(review, "review");
            finalization = Objects.requireNonNull(finalization, "finalization");
            failureFactRefs = ChainValues.nonEmptyCopy(failureFactRefs, "failureFactRefs");
            unfinishedOrSkippedItems = ChainValues.nonEmptyCopy(
                    unfinishedOrSkippedItems, "unfinishedOrSkippedItems");
            failureCategory = ChainValues.required(failureCategory, "failureCategory");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.REFLECTOR_TASK_FAILED; }
    }
}
