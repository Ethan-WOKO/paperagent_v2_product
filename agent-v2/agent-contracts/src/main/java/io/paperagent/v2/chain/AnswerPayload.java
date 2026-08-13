package io.paperagent.v2.chain;

import java.util.List;

public sealed interface AnswerPayload extends ChainProposalPayload permits
        AnswerPayload.DirectAnswer, AnswerPayload.EscalateToPersistent,
        AnswerPayload.UserQuestion, AnswerPayload.StatusOrFailure,
        AnswerPayload.FinalDelivery, AnswerPayload.DeliveryBlocked {

    record DirectAnswer(
            String routeDecisionRef,
            String directTaskSpecification,
            String inlineAnswerBody,
            List<String> factRefs) implements AnswerPayload {
        public DirectAnswer {
            routeDecisionRef = ChainValues.required(routeDecisionRef, "routeDecisionRef");
            directTaskSpecification = ChainValues.required(directTaskSpecification, "directTaskSpecification");
            inlineAnswerBody = ChainValues.required(inlineAnswerBody, "inlineAnswerBody");
            factRefs = ChainValues.copy(factRefs, "factRefs");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.ANSWER_DIRECT_ANSWER; }
    }

    record EscalateToPersistent(
            String directRouteDecisionRef,
            String escalationReason,
            List<String> requiredTools,
            List<String> requiredProjectEvidence,
            boolean persistentProgressRequired) implements AnswerPayload {
        public EscalateToPersistent {
            directRouteDecisionRef = ChainValues.required(directRouteDecisionRef, "directRouteDecisionRef");
            escalationReason = ChainValues.required(escalationReason, "escalationReason");
            requiredTools = ChainValues.copy(requiredTools, "requiredTools");
            requiredProjectEvidence = ChainValues.copy(requiredProjectEvidence, "requiredProjectEvidence");
            if (requiredTools.isEmpty() && requiredProjectEvidence.isEmpty() && !persistentProgressRequired) {
                throw new IllegalArgumentException("escalation must identify a persistent boundary");
            }
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT; }
    }

    record UserQuestion(String gapId, String inlineAnswerBody) implements AnswerPayload {
        public UserQuestion {
            gapId = ChainValues.required(gapId, "gapId");
            inlineAnswerBody = ChainValues.required(inlineAnswerBody, "inlineAnswerBody");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.ANSWER_USER_QUESTION; }
    }

    record StatusOrFailure(
            String taskOrStepStatusRef,
            String latestDecisionRef,
            String blockerOrTaskOutcomeRef,
            String inlineAnswerBody) implements AnswerPayload {
        public StatusOrFailure {
            taskOrStepStatusRef = ChainValues.required(taskOrStepStatusRef, "taskOrStepStatusRef");
            latestDecisionRef = ChainValues.required(latestDecisionRef, "latestDecisionRef");
            blockerOrTaskOutcomeRef = ChainValues.required(blockerOrTaskOutcomeRef, "blockerOrTaskOutcomeRef");
            inlineAnswerBody = ChainValues.required(inlineAnswerBody, "inlineAnswerBody");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.ANSWER_STATUS_OR_FAILURE; }
    }

    record FinalDelivery(
            String taskOutcomeRef,
            List<String> artifactAndCandidateRefs,
            String validationRef,
            String publishRef,
            String inlineAnswerBody) implements AnswerPayload {
        public FinalDelivery {
            taskOutcomeRef = ChainValues.required(taskOutcomeRef, "taskOutcomeRef");
            artifactAndCandidateRefs = ChainValues.nonEmptyCopy(
                    artifactAndCandidateRefs, "artifactAndCandidateRefs");
            validationRef = ChainValues.required(validationRef, "validationRef");
            publishRef = ChainValues.required(publishRef, "publishRef");
            inlineAnswerBody = ChainValues.required(inlineAnswerBody, "inlineAnswerBody");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.ANSWER_FINAL_DELIVERY; }
    }

    record DeliveryBlocked(
            String exactReason,
            List<String> missingOrConflictingFactRefs,
            String recoveryType) implements AnswerPayload {
        public DeliveryBlocked {
            exactReason = ChainValues.required(exactReason, "exactReason");
            missingOrConflictingFactRefs = ChainValues.nonEmptyCopy(
                    missingOrConflictingFactRefs, "missingOrConflictingFactRefs");
            recoveryType = ChainValues.required(recoveryType, "recoveryType");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.ANSWER_DELIVERY_BLOCKED; }
    }
}
