package io.paperagent.v2.chain;

public sealed interface ChainProposalPayload permits PlannerPayload, ExecutorPayload, ReflectorPayload, AnswerPayload {
    ChainProposalKind kind();

    default GapValidation gapValidation() {
        return null;
    }

    default ChainRole role() {
        return kind().role();
    }
}
