package io.paperagent.v2.chain;

import java.util.List;

public sealed interface ExecutorPayload extends ChainProposalPayload permits
        ExecutorPayload.ToolAction, ExecutorPayload.WorkspaceChange,
        ExecutorPayload.StepResult, ExecutorPayload.StepBlocked {

    record ToolAction(
            String toolId,
            String completeArguments,
            String target,
            String purpose,
            List<String> expectedOutputs,
            String requiredPermission,
            List<String> readScopes,
            List<String> writeScopes,
            String priorErrorRef,
            String priorActionRef,
            String changeFromPriorAction,
            String expectedProgress,
            GapValidation gapValidation) implements ExecutorPayload {
        public ToolAction {
            toolId = ChainValues.required(toolId, "toolId");
            completeArguments = ChainValues.required(completeArguments, "completeArguments");
            target = ChainValues.required(target, "target");
            purpose = ChainValues.required(purpose, "purpose");
            expectedOutputs = ChainValues.nonEmptyCopy(expectedOutputs, "expectedOutputs");
            requiredPermission = ChainValues.required(requiredPermission, "requiredPermission");
            readScopes = ChainValues.copy(readScopes, "readScopes");
            writeScopes = ChainValues.copy(writeScopes, "writeScopes");
            int repairFields = (priorErrorRef == null ? 0 : 1) + (priorActionRef == null ? 0 : 1)
                    + (changeFromPriorAction == null ? 0 : 1) + (expectedProgress == null ? 0 : 1);
            if (repairFields != 0 && repairFields != 4) {
                throw new IllegalArgumentException("self-repair fields must be all present or all absent");
            }
            if (repairFields == 4) {
                priorErrorRef = ChainValues.required(priorErrorRef, "priorErrorRef");
                priorActionRef = ChainValues.required(priorActionRef, "priorActionRef");
                changeFromPriorAction = ChainValues.required(changeFromPriorAction, "changeFromPriorAction");
                expectedProgress = ChainValues.required(expectedProgress, "expectedProgress");
            }
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.EXECUTOR_TOOL_ACTION; }
    }

    record WorkspaceChange(
            String baseCandidateRef,
            List<String> targetFiles,
            String inlineCanonicalChangeBody,
            String reason,
            List<String> completionConditions,
            List<String> manifestChanges,
            GapValidation gapValidation) implements ExecutorPayload {
        public WorkspaceChange {
            baseCandidateRef = ChainValues.required(baseCandidateRef, "baseCandidateRef");
            targetFiles = ChainValues.nonEmptyCopy(targetFiles, "targetFiles");
            inlineCanonicalChangeBody = ChainValues.required(inlineCanonicalChangeBody, "inlineCanonicalChangeBody");
            reason = ChainValues.required(reason, "reason");
            completionConditions = ChainValues.nonEmptyCopy(completionConditions, "completionConditions");
            manifestChanges = ChainValues.copy(manifestChanges, "manifestChanges");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE; }
    }

    record StepResult(
            List<ProposalFields.RequirementCoverage> completionConditionStatus,
            String inlineCandidateResultBody,
            List<String> artifactRefs,
            String candidateRef,
            List<String> receiptRefs,
            List<ProposalFields.ValidationSource> validationSources,
            List<String> validationRefs,
            List<String> evidenceRefs,
            List<String> unmetConditions,
            GapValidation gapValidation) implements ExecutorPayload {
        public StepResult(
                List<ProposalFields.RequirementCoverage>
                        completionConditionStatus,
                String inlineCandidateResultBody,
                List<String> artifactRefs,
                String candidateRef,
                List<String> receiptRefs,
                List<String> validationRefs,
                List<String> evidenceRefs,
                List<String> unmetConditions,
                GapValidation gapValidation) {
            this(completionConditionStatus, inlineCandidateResultBody,
                    artifactRefs, candidateRef, receiptRefs, List.of(),
                    validationRefs, evidenceRefs, unmetConditions,
                    gapValidation);
        }

        public StepResult {
            completionConditionStatus = ChainValues.nonEmptyCopy(
                    completionConditionStatus, "completionConditionStatus");
            inlineCandidateResultBody = ChainValues.required(inlineCandidateResultBody, "inlineCandidateResultBody");
            artifactRefs = ChainValues.copy(artifactRefs, "artifactRefs");
            receiptRefs = ChainValues.copy(receiptRefs, "receiptRefs");
            validationSources = ChainValues.copy(
                    validationSources, "validationSources");
            java.util.HashSet<String> requirementIds = new java.util.HashSet<>();
            for (ProposalFields.ValidationSource source : validationSources) {
                if (!requirementIds.add(source.requirementId())) {
                    throw new IllegalArgumentException(
                            "validationSources requirementId must be unique");
                }
                if (!receiptRefs.contains(source.receiptRef())) {
                    throw new IllegalArgumentException(
                            "validationSources receiptRef must be present in receiptRefs");
                }
            }
            validationRefs = ChainValues.copy(validationRefs, "validationRefs");
            evidenceRefs = ChainValues.copy(evidenceRefs, "evidenceRefs");
            unmetConditions = ChainValues.copy(unmetConditions, "unmetConditions");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.EXECUTOR_STEP_RESULT; }
    }

    record StepBlocked(
            String failureCategory,
            String errorRef,
            List<String> attemptedActionOrRepairRefs,
            String noProgressReason,
            String reviewRecommendation,
            List<String> remainingMissingFields,
            String exactQuestion,
            String expectedFormat,
            GapValidation gapValidation) implements ExecutorPayload {
        public StepBlocked {
            failureCategory = ChainValues.required(failureCategory, "failureCategory");
            errorRef = ChainValues.required(errorRef, "errorRef");
            attemptedActionOrRepairRefs = ChainValues.nonEmptyCopy(
                    attemptedActionOrRepairRefs, "attemptedActionOrRepairRefs");
            noProgressReason = ChainValues.required(noProgressReason, "noProgressReason");
            reviewRecommendation = ChainValues.required(reviewRecommendation, "reviewRecommendation");
            remainingMissingFields = ChainValues.copy(remainingMissingFields, "remainingMissingFields");
            if (remainingMissingFields.isEmpty()) {
                if (exactQuestion != null || expectedFormat != null) {
                    throw new IllegalArgumentException(
                            "question and expected format require remaining missing fields");
                }
            } else {
                exactQuestion = ChainValues.required(exactQuestion, "exactQuestion");
                expectedFormat = ChainValues.required(expectedFormat, "expectedFormat");
            }
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.EXECUTOR_STEP_BLOCKED; }
    }
}
