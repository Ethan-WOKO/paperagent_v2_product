package io.paperagent.v2.chain;

public final class ChainApplicability {
    private ChainApplicability() {
    }

    public enum SourceType {
        ACCEPT_STEP,
        PLAN_REVISION,
        USER_INSTRUCTION_DISPOSITION,
        PERSISTENT_PLAN
    }

    public enum Outcome {
        APPLICABLE,
        NOT_APPLICABLE
    }

    /** The complete eight-column applicability uniqueness identity. */
    public record Identity(
            String acceptedResultId,
            SourceType sourceType,
            String sourceDecisionId,
            String targetTaskFrameId,
            String targetPlanId,
            String targetPlanRevisionId,
            String targetCandidateKey,
            String targetInstructionVersionId) {
        public Identity {
            acceptedResultId = ChainValues.requiredAscii(acceptedResultId, "acceptedResultId");
            if (sourceType == null) throw new NullPointerException("sourceType");
            sourceDecisionId = ChainValues.requiredAscii(sourceDecisionId, "sourceDecisionId");
            targetTaskFrameId = ChainValues.requiredAscii(targetTaskFrameId, "targetTaskFrameId");
            targetPlanId = ChainValues.requiredAscii(targetPlanId, "targetPlanId");
            targetPlanRevisionId = ChainValues.requiredAscii(targetPlanRevisionId, "targetPlanRevisionId");
            targetCandidateKey = ChainValues.requiredAscii(targetCandidateKey, "targetCandidateKey");
            targetInstructionVersionId = ChainValues.requiredAscii(
                    targetInstructionVersionId, "targetInstructionVersionId");
        }
    }
}
