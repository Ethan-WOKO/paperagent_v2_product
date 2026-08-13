package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact AcceptedResult, applicability, and Step-event identity checks. */
final class ProductChainStepAcceptanceAuthority {
    private final ProductChainRecoveryAuthorityLookup authorities;

    ProductChainStepAcceptanceAuthority(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
    }

    AcceptedGraph current(
            ChainPersistenceRecords.TransitionRecord transition,
            String acceptedResultId, ChainProposalKind expectedKind) {
        AcceptedGraph graph = graph(transition.taskId(), acceptedResultId);
        ProductChainRecoveryAuthorityLookup.exact(
                graph.accepted().transitionId().equals(
                        transition.transitionId())
                        && graph.review().reviewDecisionId().equals(
                        transition.sourceDecisionId())
                        && graph.review().decisionKind() == expectedKind
                        && transition.targetIdentityDigest().equals(
                        graph.accepted().acceptedIdentitySha256()),
                "AcceptedResult does not bind its current transition");
        return graph;
    }

    AcceptedGraph priorForReadiness(
            ChainPersistenceRecords.TransitionRecord transition,
            String acceptedResultId) {
        AcceptedGraph graph = graph(transition.taskId(), acceptedResultId);
        var readinessReview = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findReviewDecisions(
                        transition.taskId()),
                value -> value.reviewDecisionId().equals(
                        transition.sourceDecisionId())
                        && value.decisionKind()
                        == ChainProposalKind.REFLECTOR_READY_TO_FINALIZE
                        && value.reviewObjectType().equals(
                        "CANDIDATE_STEP_RESULT")
                        && value.reviewObjectId().equals(
                        graph.candidate().candidateResultId()),
                "readiness ReviewDecision");
        ProductChainRecoveryAuthorityLookup.canonical(
                readinessReview.factRefs(), "readiness review fact refs");
        var accepting = authorities.workflow().findTransition(
                        graph.accepted().transitionId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "prior ACCEPT_STEP transition missing"));
        List<ChainTransitionStage> stages = authorities.workflow()
                .findTransitionStages(accepting.transitionId()).stream()
                .sorted(java.util.Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .map(ChainPersistenceRecords.TransitionStageRecord::stageCode)
                .toList();
        ProductChainRecoveryAuthorityLookup.exact(
                graph.review().decisionKind()
                        == ChainProposalKind.REFLECTOR_ACCEPT_STEP
                        && accepting.transitionType()
                        == ChainTransitionType.ACCEPT_STEP
                        && accepting.taskId().equals(transition.taskId())
                        && accepting.sourceDecisionId().equals(
                        graph.review().reviewDecisionId())
                        && accepting.targetIdentityDigest().equals(
                        graph.accepted().acceptedIdentitySha256())
                        && accepting.transitionType().isCompleteSequence(stages)
                        && transition.targetIdentityDigest().equals(
                        graph.accepted().acceptedIdentitySha256()),
                "prior AcceptedResult authority is invalid");
        return graph;
    }

    ChainCompositeTransitionRuntime.AuthorityVerification applicability(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage,
            AcceptedGraph graph, boolean emptyAllowed) {
        ProductChainRecoveryAuthorityLookup.optionalSuccessor(
                stage, "RESULT_APPLICABILITY");
        List<ChainPersistenceRecords.ResultApplicabilityRecord> sourceSet =
                authorities.workflow().findApplicabilityDecisions(
                                transition.taskId()).stream()
                        .filter(value -> value.taskId().equals(
                                transition.taskId())
                                && value.sourceType()
                                == ChainApplicability.SourceType.ACCEPT_STEP
                                && value.sourceDecisionId().equals(
                                transition.transitionId()))
                        .toList();
        if (stage.successorAuthorityType() == null) {
            ProductChainRecoveryAuthorityLookup.exact(
                    emptyAllowed && sourceSet.isEmpty(),
                    "empty applicability set is not exact");
            return ProductChainRecoveryAuthorityLookup.verifiedEmpty();
        }
        var plan = authorities.steps().findPlan(
                        transition.taskId(),
                        graph.candidate().planRevisionId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "applicability Plan snapshot missing"));
        Map<String, ChainPersistenceRecords.AcceptedResultRecord> accepted =
                new HashMap<>();
        for (var value : authorities.workflow().findAcceptedResults(
                transition.taskId())) {
            ProductChainRecoveryAuthorityLookup.exact(
                    accepted.put(value.acceptedResultId(), value) == null,
                    "AcceptedResult authority is ambiguous");
        }
        Set<ChainApplicability.Identity> identities = new HashSet<>();
        boolean currentApplicable = false;
        boolean stageRef = false;
        for (var value : sourceSet) {
            currentApplicable |= value.acceptedResultId().equals(
                    graph.accepted().acceptedResultId())
                    && value.conclusion()
                    == ChainApplicability.Outcome.APPLICABLE;
            stageRef |= value.applicabilityId().equals(
                    stage.successorAuthorityRef());
            ProductChainRecoveryAuthorityLookup.exact(
                    accepted.containsKey(value.acceptedResultId())
                            && identities.add(new ChainApplicability.Identity(
                            value.acceptedResultId(), value.sourceType(),
                            value.sourceDecisionId(),
                            value.targetTaskFrameId(), value.targetPlanId(),
                            value.targetPlanRevisionId(),
                            value.targetCandidateKey(),
                            value.targetInstructionVersionId()))
                            && value.targetTaskFrameId().equals(
                            graph.candidate().taskFrameId())
                            && value.targetPlanId().equals(
                            graph.candidate().planId())
                            && value.targetPlanRevisionId().equals(
                            graph.candidate().planRevisionId())
                            && value.targetCandidateKey().equals(
                            plan.targetCandidateKey())
                            && value.targetInstructionVersionId().equals(
                            graph.candidate().instructionId()),
                    "applicability source-set identity drift");
        }
        ProductChainRecoveryAuthorityLookup.exact(
                !sourceSet.isEmpty() && currentApplicable && stageRef,
                "applicability barrier is incomplete");
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    ChainStepAuthorityPort.StepEvent stepEvent(
            ChainPersistenceRecords.TransitionRecord transition,
            AcceptedGraph graph, String ref,
            ChainStepAuthorityPort.StepEventKind kind,
            String expectedTransitionId) {
        return ProductChainRecoveryAuthorityLookup.one(
                authorities.steps().findStepEvents(
                        transition.taskId(),
                        graph.candidate().planRevisionId()),
                value -> value.command().eventId().equals(ref)
                        && value.command().taskId().equals(transition.taskId())
                        && value.command().planRevisionId().equals(
                        graph.candidate().planRevisionId())
                        && value.command().stepId().equals(
                        graph.candidate().stepId())
                        && value.command().eventKind() == kind
                        && value.command().transitionId().equals(
                        expectedTransitionId)
                        && value.command().sourceDecisionId().equals(
                        expectedTransitionId.equals(transition.transitionId())
                                ? transition.sourceDecisionId()
                                : graph.review().reviewDecisionId()),
                "Step event identity");
    }

    void verifyNoUnfinishedStep(AcceptedGraph graph) {
        var plan = authorities.steps().findPlan(
                        graph.accepted().taskId(),
                        graph.candidate().planRevisionId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "Plan snapshot missing"));
        Set<String> terminal = new HashSet<>();
        for (var event : authorities.steps().findStepEvents(
                graph.accepted().taskId(),
                graph.candidate().planRevisionId())) {
            if (event.command().eventKind()
                    == ChainStepAuthorityPort.StepEventKind.COMPLETED
                    || event.command().eventKind()
                    == ChainStepAuthorityPort.StepEventKind
                    .SUPERSEDED_BY_REPLAN) {
                terminal.add(event.command().stepId());
            }
        }
        ProductChainRecoveryAuthorityLookup.exact(
                plan.steps().stream().allMatch(step -> terminal.contains(
                        step.stepId())),
                "empty next-Step stage leaves unfinished Steps");
    }

    private AcceptedGraph graph(String taskId, String acceptedResultId) {
        var accepted = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findAcceptedResults(taskId),
                value -> value.acceptedResultId().equals(acceptedResultId)
                        && value.taskId().equals(taskId),
                "AcceptedResult");
        var candidate = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findCandidateStepResults(taskId),
                value -> value.candidateResultId().equals(
                        accepted.candidateResultId())
                        && value.taskId().equals(taskId)
                        && value.contentId().equals(accepted.contentId()),
                "accepted CandidateStepResult");
        var review = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findReviewDecisions(taskId),
                value -> value.reviewDecisionId().equals(
                        accepted.reviewDecisionId())
                        && value.taskId().equals(taskId)
                        && value.reviewObjectType().equals(
                        "CANDIDATE_STEP_RESULT")
                        && value.reviewObjectId().equals(
                        candidate.candidateResultId()),
                "accepting ReviewDecision");
        ProductChainRecoveryAuthorityLookup.canonical(
                review.factRefs(), "review fact refs");
        String digest = ProductChainRecoveryAuthorityLookup.sha256(
                candidate.candidateResultId() + "\0"
                        + review.reviewDecisionId() + "\0"
                        + candidate.contentId());
        ProductChainRecoveryAuthorityLookup.exact(
                digest.equals(accepted.acceptedIdentitySha256()),
                "AcceptedResult digest drift");
        return new AcceptedGraph(accepted, candidate, review);
    }

    record AcceptedGraph(
            ChainPersistenceRecords.AcceptedResultRecord accepted,
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            ChainPersistenceRecords.ReviewDecisionRecord review) {
    }
}
