package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact persisted Planner-proposal, Plan and PlanBinding authority graph. */
final class ProductChainPlanBindingAuthority {
    private final ProductChainRecoveryAuthorityLookup authorities;

    ProductChainPlanBindingAuthority(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
    }

    ChainPersistenceRecords.PlanBindingRecord forTransition(
            ChainPersistenceRecords.TransitionRecord transition) {
        var stage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage.TASKFRAME_PLAN_COMMITTED,
                "PLAN_CHANGE Plan stage");
        ProductChainRecoveryAuthorityLookup.requireSuccessor(
                stage, Set.of("PLAN_BINDING"));
        return exact(transition, stage.successorAuthorityRef());
    }

    ChainPersistenceRecords.PlanBindingRecord exact(
            ChainPersistenceRecords.TransitionRecord transition, String ref) {
        var binding = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findPlanBindings(transition.taskId()),
                value -> value.planBindingId().equals(ref)
                        && value.taskId().equals(transition.taskId())
                        && Objects.equals(value.transitionId(),
                        transition.transitionId()),
                "PlanBinding");
        var snapshot = authorities.steps().findPlan(
                        transition.taskId(), binding.planRevisionId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "stable Plan snapshot missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                snapshot.taskId().equals(transition.taskId())
                        && snapshot.taskFrameId().equals(binding.taskFrameId())
                        && snapshot.planId().equals(binding.planId())
                        && snapshot.planRevisionId().equals(
                        binding.planRevisionId())
                        && snapshot.targetInstructionVersionId().equals(
                        binding.instructionId()),
                "stable Plan snapshot identity drift");
        var bootstrap = authorities.bootstraps()
                .find(new PlanId(binding.planId()))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "Plan bootstrap missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                bootstrap.taskFrame().id().value().equals(
                        binding.taskFrameId())
                        && bootstrap.plan().id().value().equals(
                        binding.planId()),
                "Plan bootstrap root identity drift");
        verifyFormalBinding(transition, binding, bootstrap);
        if (binding.planRevisionNumber() == 1) {
            verifyInitialSource(transition, binding, bootstrap);
        } else {
            verifyRevisionSource(transition, binding);
        }
        return binding;
    }

    ChainPersistenceRecords.PlanBindingRecord previous(
            ChainPersistenceRecords.PlanBindingRecord binding) {
        return ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findPlanBindings(binding.taskId()),
                value -> value.taskId().equals(binding.taskId())
                        && value.taskFrameId().equals(binding.taskFrameId())
                        && value.planId().equals(binding.planId())
                        && value.planRevisionNumber()
                        == binding.planRevisionNumber() - 1,
                "previous PlanBinding");
    }

    private void verifyFormalBinding(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.PlanBindingRecord binding,
            PersistedPlanBootstrap bootstrap) {
        String expectedAuthoritySha256 = binding.planRevisionNumber() == 1
                ? authorities.bootstrapCodec().encode(bootstrap).sha256()
                : authorities.revisionAuthorities()
                .find(binding.planId(), binding.planRevisionId())
                .filter(value -> value.revision().number()
                        == binding.planRevisionNumber())
                .filter(value -> value.revision().taskFrameId().value()
                        .equals(binding.taskFrameId()))
                .map(com.yanban.api.agent.v2.persistence
                        .ProductPlanRevisionAuthoritySource.RevisionAuthority
                        ::authoritySha256)
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "PlanBinding revision authority missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                "STABLE_V2_PLAN".equals(binding.authorityType())
                        && binding.authorityId().equals(
                        binding.planRevisionId())
                        && binding.authoritySha256().equals(
                        expectedAuthoritySha256),
                "PlanBinding stable Plan authority drift");
        var instruction = authorities.foundations().findInstruction(
                        binding.instructionId())
                .filter(value -> value.originTaskId().equals(
                        transition.taskId()))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "PlanBinding instruction authority missing"));
        var task = authorities.foundations().findTask(transition.taskId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "PlanBinding Task missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                instruction.sessionId() == task.sessionId(),
                "PlanBinding instruction crosses Task session");
        ProductChainRecoveryAuthorityLookup.one(
                authorities.foundations().findAuthorityEvents(
                        transition.taskId(), authorities.foundations()
                                .highestAuthorityEventSequence(
                                        transition.taskId())),
                value -> value.eventId().equals(binding.eventId())
                        && value.taskId().equals(transition.taskId())
                        && value.eventType().equals("PLAN_BINDING")
                        && Objects.equals(value.transitionId(),
                        transition.transitionId())
                        && value.sourceIdentitySha256().equals(
                        binding.authoritySha256())
                        && value.committedAt().truncatedTo(ChronoUnit.MICROS)
                        .equals(binding.createdAt().truncatedTo(
                                ChronoUnit.MICROS)),
                "PlanBinding authority event");
        var proposal = plannerProposal(transition, binding);
        ProductChainRecoveryAuthorityLookup.exact(
                binding.planBindingId().equals(expectedBindingId(
                        binding, proposal.proposalId())),
                "PlanBinding deterministic identity drift");
        ProductChainRecoveryAuthorityLookup.canonical(
                proposal.payload(), "Planner Plan payload");
        ProductChainRecoveryAuthorityLookup.canonical(
                proposal.sourceRefs(), "Planner Plan source refs");
        authorities.models().findInvocation(proposal.invocationId())
                .filter(value -> value.taskId().equals(transition.taskId())
                        && value.role() == ChainRole.PLANNER
                        && value.workState() == ChainWorkState.PLANNING)
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "Planner Plan invocation missing"));
        var states = authorities.models().findProposalStateEvents(
                        proposal.proposalId()).stream()
                .sorted(java.util.Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        ProductChainRecoveryAuthorityLookup.exact(
                states.size() == 2
                        && states.get(0).stateSequence() == 1
                        && states.get(0).stateKind()
                        == ChainProposalState.ACCEPTED
                        && states.get(1).stateSequence() == 2
                        && states.get(1).stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "Planner Plan proposal state prefix is invalid");
        var official = states.get(1);
        String expectedType = binding.planRevisionNumber() == 1
                ? "ROUTE_DECISION" : "PLAN_BINDING";
        String expectedRef = binding.planRevisionNumber() == 1
                ? binding.routeDecisionId() : binding.planBindingId();
        ProductChainRecoveryAuthorityLookup.exact(
                official.taskId().equals(transition.taskId())
                        && official.proposalId().equals(proposal.proposalId())
                        && expectedType.equals(
                        official.officialAuthorityType())
                        && expectedRef.equals(
                        official.officialAuthorityRef()),
                "Planner Plan proposal binds another official result");
    }

    private ChainPersistenceRecords.ModelProposalRecord plannerProposal(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        ChainProposalKind expected = binding.planRevisionNumber() == 1
                ? ChainProposalKind.PLANNER_PERSISTENT_PLAN
                : ChainProposalKind.PLANNER_PLAN_REVISION;
        if (binding.planRevisionNumber() == 1) {
            var route = ProductChainRecoveryAuthorityLookup.one(
                    authorities.workflow().findRouteDecisions(
                            transition.taskId()),
                    value -> value.routeDecisionId().equals(
                            binding.routeDecisionId()),
                    "PlanBinding RouteDecision");
            return authorities.models().findProposal(route.proposalId())
                    .filter(value -> value.taskId().equals(
                            transition.taskId())
                            && value.proposalKind() == expected)
                    .orElseThrow(() ->
                            ProductChainRecoveryAuthorityLookup.invalid(
                                    "Planner persistent proposal missing"));
        }
        List<ChainPersistenceRecords.ModelProposalRecord> matches =
                authorities.models().findInvocations(
                                transition.taskId(), Long.MAX_VALUE).stream()
                        .map(value -> authorities.models()
                                .findProposalByInvocation(
                                        value.invocationId()).orElse(null))
                        .filter(Objects::nonNull)
                        .filter(value -> value.taskId().equals(
                                transition.taskId())
                                && value.proposalKind() == expected
                                && binding.planBindingId().equals(
                                expectedBindingId(binding,
                                        value.proposalId())))
                        .toList();
        return ProductChainRecoveryAuthorityLookup.one(
                matches, ignored -> true, "Planner revision proposal");
    }

    private static String expectedBindingId(
            ChainPersistenceRecords.PlanBindingRecord binding,
            String proposalId) {
        return "plan-binding." + ProductChainRecoveryAuthorityLookup.sha256(
                binding.taskId() + "\0" + binding.instructionId() + "\0"
                        + proposalId + "\0" + binding.taskFrameId() + "\0"
                        + binding.planId() + "\0"
                        + binding.planRevisionId() + "\0"
                        + Objects.toString(binding.transitionId(), "NONE"));
    }

    private void verifyInitialSource(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.PlanBindingRecord binding,
            PersistedPlanBootstrap bootstrap) {
        ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findRouteDecisions(
                        transition.taskId()),
                value -> value.routeDecisionId().equals(
                                transition.sourceDecisionId())
                        && value.routeDecisionId().equals(
                                binding.routeDecisionId())
                        && value.route()
                        == ChainExecutionMode.PERSISTENT_PLAN_EXECUTE
                        && value.decisionKind()
                        == ChainPersistenceRecords.RouteDecisionType.INITIAL,
                "initial persistent RouteDecision");
        ProductChainRecoveryAuthorityLookup.exact(
                bootstrap.plan().revisions().stream().anyMatch(
                        revision -> revision.id().value().equals(
                                binding.planRevisionId())
                                && revision.number()
                                == binding.planRevisionNumber()),
                "initial Plan bootstrap revision drift");
    }

    private void verifyRevisionSource(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        var prior = previous(binding);
        ProductChainRecoveryAuthorityLookup.exact(
                prior.routeDecisionId().equals(binding.routeDecisionId()),
                "Plan revision changed the root RouteDecision");
        boolean review = authorities.workflow().findReviewDecisions(
                        transition.taskId()).stream()
                .anyMatch(value -> value.reviewDecisionId().equals(
                        transition.sourceDecisionId())
                        && value.decisionKind()
                        == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED);
        boolean gap = authorities.workflow().findPendingItems(
                        transition.taskId()).stream()
                .filter(value -> value.gapId().equals(
                        transition.sourceDecisionId()))
                .anyMatch(value -> {
                    var events = authorities.workflow()
                            .findPendingItemEvents(value.gapId());
                    return !events.isEmpty()
                            && events.get(events.size() - 1).eventKind()
                            == ChainPendingItemStatus.RESOLVED;
                });
        ProductChainRecoveryAuthorityLookup.exact(review ^ gap,
                "Plan revision source is missing or ambiguous");
    }
}
