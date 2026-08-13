package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Reuses the formal typed pending-item validation authority. */
final class ProductChainGapValidationAuthority {
    private final ProductChainRecoveryAuthorityLookup authorities;

    ProductChainGapValidationAuthority(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
    }

    void verify(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord normal,
            ChainPersistenceRecords.PendingItemRecord resolvedItem,
            ChainPersistenceRecords.PendingItemEventRecord resolvedEvent,
            boolean boundRequired) {
        GapRound round = resolvedEvent == null
                ? pendingRound(transition)
                : pendingRound(resolvedItem, resolvedEvent);
        var invocation = authorities.models().findInvocation(
                        transition.sourceDecisionId())
                .filter(value -> value.taskId().equals(transition.taskId()))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "gap validation invocation missing"));
        var proposal = authorities.models().findProposalByInvocation(
                        invocation.invocationId())
                .filter(value -> value.taskId().equals(transition.taskId())
                        && value.invocationId().equals(invocation.invocationId()))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "gap validation proposal missing"));
        ProductChainRecoveryAuthorityLookup.canonical(
                proposal.payload(), "gap validation payload");
        ProductChainRecoveryAuthorityLookup.canonical(
                proposal.sourceRefs(), "gap validation source refs");
        var states = authorities.models().findProposalStateEvents(
                        proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        ProductChainRecoveryAuthorityLookup.exact(
                (boundRequired ? states.size() == 2
                        : states.size() == 1 || states.size() == 2)
                        && states.get(0).stateKind()
                        == ChainProposalState.ACCEPTED
                        && states.get(0).taskId().equals(transition.taskId())
                        && states.get(0).proposalId().equals(
                        proposal.proposalId())
                        && states.get(0).stateSequence() == 1,
                "gap validation proposal is not accepted");
        if (states.size() == 2) {
            var bound = states.get(1);
            ProductChainRecoveryAuthorityLookup.exact(
                    bound.stateSequence() == 2
                            && bound.stateKind()
                            == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                            && bound.taskId().equals(transition.taskId())
                            && bound.proposalId().equals(proposal.proposalId())
                            && normal.successorAuthorityType().equals(
                            bound.officialAuthorityType())
                            && normal.successorAuthorityRef().equals(
                            bound.officialAuthorityRef()),
                    "gap proposal binds another normal successor");
        }
        String raw = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        var payload = new StrictChainProviderOutputParser().parse(
                raw, invocation.role(), invocation.workState(),
                round.item().gapId()).payload();
        var validation = new ChainPendingItemRuntime.AcceptedGapValidation(
                proposal, states.get(states.size() - 1), invocation, payload);
        ChainPendingItemRuntime.validateGapProposalAuthority(
                round.item(), ChainPendingItemStatus.RESPONSE_RECEIVED,
                round.response().responseRound(),
                round.response().answerInstructionId(), validation);
        ProductChainRecoveryAuthorityLookup.exact(
                validation.validation().outcome()
                        == GapValidation.Outcome.RESOLVED,
                "gap validation outcome is not RESOLVED");
    }

    private GapRound pendingRound(
            ChainPersistenceRecords.TransitionRecord transition) {
        List<GapRound> matches = new ArrayList<>();
        for (var item : authorities.workflow().findPendingItems(
                transition.taskId())) {
            var events = authorities.workflow().findPendingItemEvents(
                    item.gapId());
            if (!events.isEmpty()) {
                var response = events.get(events.size() - 1);
                if (response.eventKind()
                        == ChainPendingItemStatus.RESPONSE_RECEIVED
                        && targetDigest(transition, item, response)) {
                    matches.add(new GapRound(item, response));
                }
            }
        }
        return ProductChainRecoveryAuthorityLookup.one(
                matches, ignored -> true, "gap validation response round");
    }

    private GapRound pendingRound(
            ChainPersistenceRecords.PendingItemRecord item,
            ChainPersistenceRecords.PendingItemEventRecord resolved) {
        var events = authorities.workflow().findPendingItemEvents(
                item.gapId());
        int index = -1;
        for (int current = 0; current < events.size(); current++) {
            var event = events.get(current);
            ProductChainRecoveryAuthorityLookup.exact(
                    event.taskId().equals(item.taskId())
                            && event.gapId().equals(item.gapId()),
                    "gap event prefix identity drift");
            if (event.eventId().equals(resolved.eventId())) {
                ProductChainRecoveryAuthorityLookup.exact(index < 0,
                        "resolved gap event is duplicated");
                index = current;
            }
        }
        ProductChainRecoveryAuthorityLookup.exact(
                index == events.size() - 1 && index > 0,
                "resolved event is not the latest response-round event");
        var response = events.get(index - 1);
        ProductChainRecoveryAuthorityLookup.exact(
                response.eventKind()
                        == ChainPendingItemStatus.RESPONSE_RECEIVED
                        && response.responseRound() == resolved.responseRound()
                        && response.answerInstructionId() != null
                        && response.validationInvocationId() == null
                        && response.gapValidationOutcome() == null,
                "resolved gap does not follow its exact response round");
        return new GapRound(item, response);
    }

    private static boolean targetDigest(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.PendingItemRecord item,
            ChainPersistenceRecords.PendingItemEventRecord response) {
        return transition.targetIdentityDigest().equals(
                ProductChainRecoveryAuthorityLookup.sha256(
                        transition.taskId() + "\0" + item.gapId() + "\0"
                                + response.responseRound() + "\0"
                                + transition.sourceDecisionId()));
    }

    private record GapRound(
            ChainPersistenceRecords.PendingItemRecord item,
            ChainPersistenceRecords.PendingItemEventRecord response) {
    }
}
