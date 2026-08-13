package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.util.Objects;
import java.util.Set;

/** Verifies one non-final Step acceptance transition. */
final class ProductChainAcceptStepStageVerifier
        implements ProductChainTransitionStageVerifier {
    private final ProductChainRecoveryAuthorityLookup authorities;
    private final ProductChainStepAcceptanceAuthority acceptance;
    private final ProductChainStepScheduleAuthority schedule;

    ProductChainAcceptStepStageVerifier(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.acceptance = new ProductChainStepAcceptanceAuthority(authorities);
        this.schedule = new ProductChainStepScheduleAuthority(authorities);
    }

    @Override
    public ChainCompositeTransitionRuntime.AuthorityVerification verify(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        return switch (stage.stageCode()) {
            case OPEN, COMPLETE ->
                    ProductChainRecoveryAuthorityLookup.verifiedNone(stage);
            case ACCEPTED_RESULT_COMMITTED -> {
                ProductChainRecoveryAuthorityLookup.requireSuccessor(
                        stage, Set.of("ACCEPTED_RESULT"));
                acceptance.current(transition, stage.successorAuthorityRef(),
                        ChainProposalKind.REFLECTOR_ACCEPT_STEP);
                yield ProductChainRecoveryAuthorityLookup.verified();
            }
            case APPLICABILITY_COMMITTED -> acceptance.applicability(
                    transition, stage, accepted(transition), false);
            case STEP_COMPLETED -> {
                ProductChainRecoveryAuthorityLookup.requireSuccessor(
                        stage, Set.of("STEP_EVENT"));
                acceptance.stepEvent(transition, accepted(transition),
                        stage.successorAuthorityRef(),
                        ChainStepAuthorityPort.StepEventKind.COMPLETED,
                        transition.transitionId());
                yield ProductChainRecoveryAuthorityLookup.verified();
            }
            case NEXT_STEP_ACTIVATED_OR_NONE -> verifyNext(transition, stage);
            default -> throw ProductChainRecoveryAuthorityLookup.invalid(
                    "unsupported ACCEPT_STEP stage");
        };
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyNext(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.optionalSuccessor(
                stage, "STEP_EVENT");
        var graph = accepted(transition);
        var completionStage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage.STEP_COMPLETED,
                "ACCEPT_STEP completion stage");
        ProductChainRecoveryAuthorityLookup.requireSuccessor(
                completionStage, Set.of("STEP_EVENT"));
        acceptance.stepEvent(transition, graph,
                completionStage.successorAuthorityRef(),
                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                transition.transitionId());
        if (stage.successorAuthorityType() == null) {
            schedule.exactNoNext(
                    transition, graph.candidate().planRevisionId());
            return ProductChainRecoveryAuthorityLookup.verified();
        }
        schedule.exactActivation(transition,
                graph.candidate().planRevisionId(),
                stage.successorAuthorityRef(), false);
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private ProductChainStepAcceptanceAuthority.AcceptedGraph accepted(
            ChainPersistenceRecords.TransitionRecord transition) {
        var stage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage.ACCEPTED_RESULT_COMMITTED,
                "ACCEPT_STEP AcceptedResult stage");
        return acceptance.current(transition,
                stage.successorAuthorityRef(),
                ChainProposalKind.REFLECTOR_ACCEPT_STEP);
    }
}
