package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.step.ChainStepStateMachine;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bridges transition identities to the formal Step state machine. */
final class ProductChainStepScheduleAuthority {
    private final ProductChainRecoveryAuthorityLookup authorities;

    ProductChainStepScheduleAuthority(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
    }

    ChainStepAuthorityPort.StepEvent exactActivation(
            ChainPersistenceRecords.TransitionRecord transition,
            String planRevisionId, String eventId, boolean firstPlanStep) {
        List<ChainStepAuthorityPort.StepEvent> events = authorities.steps()
                .findStepEvents(transition.taskId(), planRevisionId).stream()
                .sorted(Comparator.comparingLong(
                        ChainStepAuthorityPort.StepEvent::authoritySequence))
                .toList();
        var activation = ProductChainRecoveryAuthorityLookup.one(
                events,
                value -> value.command().eventId().equals(eventId)
                        && value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED
                        && value.command().taskId().equals(
                        transition.taskId())
                        && value.command().planRevisionId().equals(
                        planRevisionId)
                        && value.command().transitionId().equals(
                        transition.transitionId())
                        && value.command().sourceDecisionId().equals(
                        transition.sourceDecisionId()),
                "Step activation transition identity");
        ProductChainRecoveryAuthorityLookup.exact(
                events.stream().filter(value ->
                        value.command().eventKind()
                                == ChainStepAuthorityPort.StepEventKind.ACTIVATED
                                && value.command().transitionId().equals(
                                transition.transitionId())
                                && value.command().sourceDecisionId().equals(
                                transition.sourceDecisionId())).count() == 1,
                "Step activation source set is not unique");
        int cut = events.indexOf(activation);
        ProductChainRecoveryAuthorityLookup.exact(
                cut >= 0 && (!firstPlanStep || cut == 0),
                "PLAN_CHANGE activation is not the first Plan event");
        machine(authorities.steps()).derive(
                transition.taskId(), planRevisionId);
        var immediatelyAfter = machine(new PrefixStepAuthority(
                authorities.steps(), transition.taskId(), planRevisionId,
                events.subList(0, cut + 1))).derive(
                transition.taskId(), planRevisionId);
        ProductChainRecoveryAuthorityLookup.exact(
                immediatelyAfter.activeStep().isPresent()
                        && immediatelyAfter.activeStep().orElseThrow()
                        .stepId().equals(
                        activation.command().stepId())
                        && Objects.equals(
                        immediatelyAfter.activeStep().orElseThrow()
                                .activationEventId(),
                        activation.command().activationEventId()),
                "activation prefix does not derive its formal active Step");
        var before = machine(new PrefixStepAuthority(
                authorities.steps(), transition.taskId(), planRevisionId,
                events.subList(0, cut))).derive(
                transition.taskId(), planRevisionId);
        var expected = before.steps().stream()
                .filter(value -> value.status() == ChainStepStatus.READY)
                .min(Comparator.comparingInt(
                        ChainStepStateMachine.StepState::stableOrder))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "activation prefix has no formally READY Step"));
        ProductChainRecoveryAuthorityLookup.exact(
                before.activeStep().isEmpty()
                        && expected.stepId().equals(
                        activation.command().stepId())
                        && activation.command().eventId().equals(
                        "step.activation."
                                + ProductChainRecoveryAuthorityLookup.sha256(
                                transition.taskId() + "\0"
                                        + planRevisionId + "\0"
                                        + expected.stepId() + "\0"
                                        + transition.transitionId())),
                "activation differs from the formal scheduler choice");
        return activation;
    }

    void exactNoNext(
            ChainPersistenceRecords.TransitionRecord transition,
            String planRevisionId) {
        var state = machine(authorities.steps()).derive(
                transition.taskId(), planRevisionId);
        ProductChainRecoveryAuthorityLookup.exact(
                state.activeStep().isEmpty()
                        && !state.schedulingBlocked()
                        && state.steps().stream().noneMatch(value ->
                        value.status() == ChainStepStatus.READY),
                "empty next-Step stage is not formal NO_STEP");
    }

    ChainStepAuthorityPort.PlanSnapshot exactAllTerminal(
            String taskId, String planRevisionId, String finalStepId) {
        var state = machine(authorities.steps()).derive(
                taskId, planRevisionId);
        int finalOrder = state.steps().stream()
                .mapToInt(ChainStepStateMachine.StepState::stableOrder)
                .max().orElseThrow();
        ProductChainRecoveryAuthorityLookup.exact(
                state.activeStep().isEmpty()
                        && !state.schedulingBlocked()
                        && state.steps().stream().allMatch(value ->
                        value.status() == ChainStepStatus.COMPLETED
                                || value.status()
                                == ChainStepStatus.SUPERSEDED_BY_REPLAN)
                        && state.steps().stream().anyMatch(value ->
                        value.stepId().equals(finalStepId)
                                && value.stableOrder() == finalOrder),
                "readiness does not bind a fully terminal Plan");
        return state.plan();
    }

    private ChainStepStateMachine machine(ChainStepAuthorityPort steps) {
        return new ChainStepStateMachine(
                steps, authorities.workflow(), authorities.foundations(),
                authorities.models(), authorities.contexts());
    }

    private record PrefixStepAuthority(
            ChainStepAuthorityPort delegate,
            String taskId,
            String planRevisionId,
            List<ChainStepAuthorityPort.StepEvent> prefix)
            implements ChainStepAuthorityPort {
        private PrefixStepAuthority {
            Objects.requireNonNull(delegate, "delegate");
            prefix = List.copyOf(prefix);
        }

        @Override
        public Optional<PlanSnapshot> findPlan(
                String requestedTaskId, String requestedRevisionId) {
            return delegate.findPlan(requestedTaskId, requestedRevisionId);
        }

        @Override
        public List<StepEvent> findStepEvents(
                String requestedTaskId, String requestedRevisionId) {
            if (taskId.equals(requestedTaskId)
                    && planRevisionId.equals(requestedRevisionId)) {
                return prefix;
            }
            return delegate.findStepEvents(
                    requestedTaskId, requestedRevisionId);
        }

        @Override
        public ChainPersistenceRecords.AppendResult<StepEvent> appendStepEvent(
                StepEventCommand command) {
            throw new UnsupportedOperationException(
                    "read-only recovery prefix");
        }
    }
}
