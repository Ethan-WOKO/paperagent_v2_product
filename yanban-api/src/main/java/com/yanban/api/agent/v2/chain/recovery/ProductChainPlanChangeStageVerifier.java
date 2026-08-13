package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verifies both initial and revised PLAN_CHANGE authority shapes. */
final class ProductChainPlanChangeStageVerifier
        implements ProductChainTransitionStageVerifier {
    private final ProductChainRecoveryAuthorityLookup authorities;
    private final ProductChainStepScheduleAuthority schedule;
    private final ProductChainPlanBindingAuthority plans;

    ProductChainPlanChangeStageVerifier(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.schedule = new ProductChainStepScheduleAuthority(authorities);
        this.plans = new ProductChainPlanBindingAuthority(authorities);
    }

    @Override
    public ChainCompositeTransitionRuntime.AuthorityVerification verify(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        return switch (stage.stageCode()) {
            case OPEN, COMPLETE ->
                    ProductChainRecoveryAuthorityLookup.verifiedNone(stage);
            case TASKFRAME_PLAN_COMMITTED -> {
                ProductChainRecoveryAuthorityLookup.requireSuccessor(
                        stage, Set.of("PLAN_BINDING"));
                plans.exact(transition, stage.successorAuthorityRef());
                yield ProductChainRecoveryAuthorityLookup.verified();
            }
            case APPLICABILITY_COMMITTED ->
                    verifyApplicability(transition, stage);
            case OLD_STEP_SUPERSEDED_OR_NONE ->
                    verifyOldStep(transition, stage);
            case NEW_STEP_ACTIVATED -> verifyNewStep(transition, stage);
            default -> throw ProductChainRecoveryAuthorityLookup.invalid(
                    "unsupported PLAN_CHANGE stage");
        };
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification
            verifyApplicability(
                    ChainPersistenceRecords.TransitionRecord transition,
                    ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.optionalSuccessor(
                stage, "RESULT_APPLICABILITY");
        var binding = plans.forTransition(transition);
        var plan = authorities.steps().findPlan(
                        transition.taskId(), binding.planRevisionId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "PLAN_CHANGE Plan snapshot missing"));
        List<ChainPersistenceRecords.ResultApplicabilityRecord> sourceSet =
                authorities.workflow().findApplicabilityDecisions(
                                transition.taskId()).stream()
                        .filter(value -> value.taskId().equals(
                                transition.taskId())
                                && value.sourceDecisionId().equals(
                                transition.transitionId())
                                && (value.sourceType()
                                == ChainApplicability.SourceType.PLAN_REVISION
                                || value.sourceType()
                                == ChainApplicability.SourceType
                                .PERSISTENT_PLAN))
                        .toList();
        if (stage.successorAuthorityType() == null) {
            ProductChainRecoveryAuthorityLookup.exact(sourceSet.isEmpty(),
                    "empty PLAN_CHANGE applicability set is not exact");
            return ProductChainRecoveryAuthorityLookup.verifiedEmpty();
        }
        Set<ChainApplicability.Identity> identities = new HashSet<>();
        Set<String> acceptedIds = authorities.workflow()
                .findAcceptedResults(transition.taskId()).stream()
                .map(ChainPersistenceRecords.AcceptedResultRecord
                        ::acceptedResultId)
                .collect(java.util.stream.Collectors.toSet());
        boolean stageRef = false;
        for (var value : sourceSet) {
            stageRef |= value.applicabilityId().equals(
                    stage.successorAuthorityRef());
            ProductChainRecoveryAuthorityLookup.exact(
                    acceptedIds.contains(value.acceptedResultId())
                            && identities.add(new ChainApplicability.Identity(
                            value.acceptedResultId(), value.sourceType(),
                            value.sourceDecisionId(),
                            value.targetTaskFrameId(), value.targetPlanId(),
                            value.targetPlanRevisionId(),
                            value.targetCandidateKey(),
                            value.targetInstructionVersionId()))
                            && value.targetTaskFrameId().equals(
                            binding.taskFrameId())
                            && value.targetPlanId().equals(binding.planId())
                            && value.targetPlanRevisionId().equals(
                            binding.planRevisionId())
                            && value.targetCandidateKey().equals(
                            plan.targetCandidateKey())
                            && value.targetInstructionVersionId().equals(
                            binding.instructionId()),
                    "PLAN_CHANGE applicability identity drift");
        }
        ProductChainRecoveryAuthorityLookup.exact(
                !sourceSet.isEmpty() && stageRef,
                "PLAN_CHANGE applicability barrier is incomplete");
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification
            verifyOldStep(
                    ChainPersistenceRecords.TransitionRecord transition,
                    ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.optionalSuccessor(
                stage, "STEP_EVENT");
        var binding = plans.forTransition(transition);
        List<ChainStepAuthorityPort.StepEvent> matching = authorities
                .transitionStepEvents(transition).stream()
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind
                        .SUPERSEDED_BY_REPLAN)
                .toList();
        if (stage.successorAuthorityType() == null) {
            ProductChainRecoveryAuthorityLookup.exact(matching.isEmpty(),
                    "empty supersession authority set is not exact");
            return ProductChainRecoveryAuthorityLookup.verified();
        }
        ProductChainRecoveryAuthorityLookup.exact(
                binding.planRevisionNumber() > 1,
                "initial Plan cannot supersede an old Step");
        var previous = plans.previous(binding);
        ProductChainRecoveryAuthorityLookup.one(matching,
                value -> value.command().eventId().equals(
                                stage.successorAuthorityRef())
                        && value.command().planRevisionId().equals(
                                previous.planRevisionId()),
                "superseded old Step");
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyNewStep(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.requireSuccessor(
                stage, Set.of("STEP_EVENT"));
        var binding = plans.forTransition(transition);
        schedule.exactActivation(transition, binding.planRevisionId(),
                stage.successorAuthorityRef(), true);
        return ProductChainRecoveryAuthorityLookup.verified();
    }

}
