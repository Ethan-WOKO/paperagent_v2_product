package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verifies the two formal authorities of a resolved pending-item transition. */
final class ProductChainGapStageVerifier
        implements ProductChainTransitionStageVerifier {
    private static final Set<String> SUCCESSOR_TYPES = Set.of(
            "ROUTE_DECISION", "PLAN_BINDING", "TRANSITION",
             "ACTION_BINDING", "WORKSPACE_CANDIDATE",
             "CANDIDATE_STEP_RESULT", "PENDING_ITEM",
             "TASK_OUTCOME", "MODEL_INVOCATION",
             "INSTRUCTION_DISPOSITION");
    private final ProductChainRecoveryAuthorityLookup authorities;
    private final ProductChainGapValidationAuthority validations;

    ProductChainGapStageVerifier(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.validations = new ProductChainGapValidationAuthority(authorities);
    }

    @Override
    public ChainCompositeTransitionRuntime.AuthorityVerification verify(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        return switch (stage.stageCode()) {
            case OPEN, COMPLETE ->
                    ProductChainRecoveryAuthorityLookup.verifiedNone(stage);
            case NORMAL_SUCCESSOR_COMMITTED -> {
                ProductChainRecoveryAuthorityLookup.requireSuccessor(
                        stage, SUCCESSOR_TYPES);
                verifyNormalSuccessor(transition, stage);
                validations.verify(transition, stage, null, null, false);
                yield ProductChainRecoveryAuthorityLookup.verified();
            }
            case PENDING_RESOLVED -> {
                ProductChainRecoveryAuthorityLookup.requireSuccessor(
                        stage, Set.of("PENDING_ITEM_EVENT"));
                verifyResolved(transition, stage);
                yield ProductChainRecoveryAuthorityLookup.verified();
            }
            default -> throw ProductChainRecoveryAuthorityLookup.invalid(
                    "unsupported GAP_RESOLUTION stage");
        };
    }

    private void verifyResolved(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        List<GapEvent> matches = new ArrayList<>();
        for (var item : authorities.workflow().findPendingItems(
                transition.taskId())) {
            for (var event : authorities.workflow().findPendingItemEvents(
                    item.gapId())) {
                if (event.eventId().equals(stage.successorAuthorityRef())) {
                    matches.add(new GapEvent(item, event));
                }
            }
        }
        GapEvent match = ProductChainRecoveryAuthorityLookup.one(
                matches, ignored -> true, "gap resolution event");
        ProductChainRecoveryAuthorityLookup.canonical(
                match.event().detail(), "pending event detail");
        ProductChainRecoveryAuthorityLookup.exact(
                match.event().taskId().equals(transition.taskId())
                        && match.event().gapId().equals(match.item().gapId())
                        && match.event().eventKind()
                        == ChainPendingItemStatus.RESOLVED
                        && match.event().gapValidationOutcome()
                        == GapValidation.Outcome.RESOLVED
                        && Objects.equals(
                        match.event().validationInvocationId(),
                        transition.sourceDecisionId())
                        && transition.targetIdentityDigest().equals(
                        ProductChainRecoveryAuthorityLookup.sha256(
                                transition.taskId() + "\0"
                                        + match.item().gapId() + "\0"
                                        + match.event().responseRound() + "\0"
                                        + transition.sourceDecisionId())),
                "gap resolution identity drift");
        var normal = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                "gap normal-successor stage");
        ProductChainRecoveryAuthorityLookup.exact(
                match.event().detail().json().equals(
                        "{\"successorAuthorityRef\":\""
                                + normal.successorAuthorityRef() + "\"}"),
                "resolved event names another normal successor");
        validations.verify(transition, normal, match.item(), match.event(),
                true);
    }

    private void verifyNormalSuccessor(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        String ref = stage.successorAuthorityRef();
        switch (stage.successorAuthorityType()) {
            case "ROUTE_DECISION" -> ProductChainRecoveryAuthorityLookup.one(
                    authorities.workflow().findRouteDecisions(
                            transition.taskId()),
                    value -> value.routeDecisionId().equals(ref)
                            && value.taskId().equals(transition.taskId()),
                    "ROUTE_DECISION");
            case "PLAN_BINDING" -> ProductChainRecoveryAuthorityLookup.one(
                    authorities.workflow().findPlanBindings(
                            transition.taskId()),
                    value -> value.planBindingId().equals(ref)
                            && value.taskId().equals(transition.taskId()),
                    "PLAN_BINDING");
            case "TRANSITION" -> verifyNestedTransition(transition, ref);
            case "ACTION_BINDING" -> ProductChainRecoveryAuthorityLookup.one(
                    authorities.workflow().findActionBindings(
                            transition.taskId()),
                    value -> value.actionId().equals(ref)
                            && value.taskId().equals(transition.taskId()),
                    "ACTION_BINDING");
            case "WORKSPACE_CANDIDATE" ->
                    ProductChainRecoveryAuthorityLookup.one(
                            authorities.workflow().findWorkspaceCandidates(
                                    transition.taskId()),
                            value -> value.workspaceCandidateId().equals(ref)
                                    && value.taskId().equals(
                                    transition.taskId()),
                            "WORKSPACE_CANDIDATE");
            case "CANDIDATE_STEP_RESULT" ->
                    ProductChainRecoveryAuthorityLookup.one(
                            authorities.workflow().findCandidateStepResults(
                                    transition.taskId()),
                            value -> value.candidateResultId().equals(ref)
                                    && value.taskId().equals(
                                    transition.taskId()),
                            "CANDIDATE_STEP_RESULT");
            case "PENDING_ITEM" -> ProductChainRecoveryAuthorityLookup.one(
                    authorities.workflow().findPendingItems(
                            transition.taskId()),
                    value -> value.gapId().equals(ref)
                            && value.taskId().equals(transition.taskId()),
                    "PENDING_ITEM");
            case "TASK_OUTCOME" -> authorities.finalization()
                    .findTaskOutcome(transition.taskId())
                    .filter(value -> value.outcomeId().equals(ref)
                            && value.taskId().equals(transition.taskId()))
                    .orElseThrow(() ->
                            ProductChainRecoveryAuthorityLookup.invalid(
                                    "TASK_OUTCOME missing"));
             case "MODEL_INVOCATION" -> authorities.models().findInvocation(ref)
                     .filter(value -> value.taskId().equals(
                             transition.taskId()))
                     .orElseThrow(() ->
                             ProductChainRecoveryAuthorityLookup.invalid(
                                     "MODEL_INVOCATION missing"));
             case "INSTRUCTION_DISPOSITION" ->
                     ProductChainRecoveryAuthorityLookup.one(
                             authorities.workflow().findInstructionDispositions(
                                     transition.taskId()),
                             value -> value.dispositionId().equals(ref)
                                     && value.taskId().equals(
                                     transition.taskId()),
                             "INSTRUCTION_DISPOSITION");
            default -> throw ProductChainRecoveryAuthorityLookup.invalid(
                    "unknown normal successor type");
        }
    }

    private void verifyNestedTransition(
            ChainPersistenceRecords.TransitionRecord parent, String ref) {
        var nested = authorities.workflow().findTransition(ref)
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "nested transition missing"));
        var prefix = authorities.workflow().findTransitionStages(ref).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .map(ChainPersistenceRecords.TransitionStageRecord::stageCode)
                .toList();
        ProductChainRecoveryAuthorityLookup.exact(
                nested.taskId().equals(parent.taskId())
                        && !nested.transitionId().equals(parent.transitionId())
                        && nested.transitionType().isCompleteSequence(prefix),
                "nested transition identity drift");
    }

    private record GapEvent(
            ChainPersistenceRecords.PendingItemRecord item,
            ChainPersistenceRecords.PendingItemEventRecord event) {
    }

}
