package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Role-specific model metadata that requires formal proposal authority. */
final class ProductModelRoleMetadataValues {
    private ProductModelRoleMetadataValues() {
    }

    static ChainContextValue reviewedCandidate(
            ChainPersistenceRecords.ContextRevisionRecord building,
            List<ProductModelInvocationProjectionValues.InvocationView>
                    values) {
        if ("STEP_BLOCKED_REVIEW".equals(building.callReason())) {
            return reviewedStepBlock(building, values);
        }
        var reviewed = values.stream().filter(value ->
                value.proposal() != null && value.proposal().proposalKind()
                        == ChainProposalKind.EXECUTOR_STEP_RESULT)
                .filter(value -> sameReviewedScope(
                        building, value.context()))
                .filter(ProductModelRoleMetadataValues::isFormalCandidate)
                .reduce((left, right) -> right).orElse(null);
        Map<String, ChainContextValue> result = new TreeMap<>();
        result.put("candidateFingerprint", building.candidateFingerprint()
                == null ? ChainContextValue.nil()
                : ref(building.candidateFingerprint()));
        result.put("candidateArtifactId", building.candidateArtifactId()
                == null ? ChainContextValue.nil()
                : ChainContextValue.number(building.candidateArtifactId()));
        result.put("executorProposal", reviewed == null
                ? ProductModelInvocationProjectionValues.none(
                "NO_FORMAL_CANDIDATE_STEP_RESULT")
                : ProductModelInvocationProjectionValues.invocation(
                        reviewed));
        result.put("officialCandidateResultRef", reviewed == null
                ? ChainContextValue.nil()
                : ref(reviewed.states().get(reviewed.states().size() - 1)
                        .officialAuthorityRef()));
        return ChainContextValue.object(result);
    }

    private static ChainContextValue reviewedStepBlock(
            ChainPersistenceRecords.ContextRevisionRecord building,
            List<ProductModelInvocationProjectionValues.InvocationView>
                    values) {
        var blocked = values.stream().filter(value ->
                        value.proposal() != null
                                && value.proposal().proposalKind()
                                == ChainProposalKind.EXECUTOR_STEP_BLOCKED)
                .filter(value -> sameReviewedScope(building, value.context()))
                .filter(value -> !value.states().isEmpty()
                        && value.states().get(value.states().size() - 1)
                        .stateKind() == ChainProposalState.ACCEPTED)
                .reduce((left, right) -> right).orElse(null);
        if (blocked == null) {
            throw ProductChainContextProjectionSupport.blocked(
                    io.paperagent.v2.chain.ChainContextModule
                            .MODEL_INVOCATIONS_AND_PROPOSALS,
                    "STEP_BLOCKED review lacks one accepted formal source");
        }
        return ChainContextValue.object(Map.of(
                "reviewSourceType", ChainContextValue.text("PROPOSAL_STATE"),
                "executorProposal",
                ProductModelInvocationProjectionValues.invocation(blocked),
                "officialCandidateResultRef", ChainContextValue.nil()));
    }

    private static boolean isFormalCandidate(
            ProductModelInvocationProjectionValues.InvocationView value) {
        if (value.states().isEmpty()) return false;
        var latest = value.states().get(value.states().size() - 1);
        return latest.stateKind()
                == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                && "CANDIDATE_STEP_RESULT".equals(
                latest.officialAuthorityType());
    }

    private static boolean sameReviewedScope(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.ContextRevisionRecord executor) {
        return Objects.equals(building.planId(), executor.planId())
                && Objects.equals(building.planRevisionId(),
                executor.planRevisionId())
                && Objects.equals(building.stepId(), executor.stepId())
                && Objects.equals(building.activationEventId(),
                executor.activationEventId());
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }
}
