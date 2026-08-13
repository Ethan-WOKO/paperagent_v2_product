package io.paperagent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Fixed recovery coordinator for the ten frozen fact categories. Persisted
 * composite transitions are resumed before action reconciliation and role
 * selection; the next role is never stored independently.
 */
public final class ChainRecoveryRuntime {
    private final RecoverySource source;
    private final CompositeTransitionRecovery transitions;
    private final InFlightActionRecovery inFlightActions;
    private final NextRoleSelector roleSelector;

    public ChainRecoveryRuntime(
            RecoverySource source,
            CompositeTransitionRecovery transitions,
            InFlightActionRecovery inFlightActions,
            NextRoleSelector roleSelector) {
        this.source = Objects.requireNonNull(source, "source");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.inFlightActions = Objects.requireNonNull(inFlightActions, "inFlightActions");
        this.roleSelector = Objects.requireNonNull(roleSelector, "roleSelector");
    }

    public RecoveryOutcome recover(RecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        RecoverySnapshot initial = source.load(request.taskId());
        require(initial.taskId().equals(request.taskId()),
                "recovery source returned another task");
        List<TransitionRecoveryResult> resumed = new ArrayList<>();
        List<TransitionRef> orderedTransitions = initial.incompleteTransitions().stream()
                .sorted(Comparator.comparingLong(TransitionRef::authoritySequence)
                        .thenComparing(TransitionRef::transitionId))
                .toList();
        TransitionRecoveryResult waiting = null;
        for (TransitionRef transition : orderedTransitions) {
            TransitionRecoveryResult result = transitions.resume(transition);
            require(result.transitionId().equals(transition.transitionId())
                            && result.transitionType() == transition.transitionType(),
                    "composite transition recovery returned another transition");
            resumed.add(result);
            if (result.disposition() == TransitionRecoveryDisposition.COMPLETED) {
                require(result.lastStage() == ChainTransitionStage.COMPLETE,
                        "completed transition recovery lacks COMPLETE");
                continue;
            }
            require(result.transitionType() == ChainTransitionType.FINALIZATION
                            && result.lastStage()
                            == ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                    "only a formal finalization failure may await a successor");
            waiting = result;
            break;
        }

        RecoverySnapshot afterTransitions = source.load(request.taskId());
        if (waiting != null) {
            TransitionRecoveryResult formalWait = waiting;
            require(afterTransitions.taskId().equals(request.taskId())
                            && afterTransitions.incompleteTransitions().stream()
                            .anyMatch(value -> value.transitionId().equals(
                                            formalWait.transitionId())
                                    && value.transitionType()
                                    == formalWait.transitionType()
                                    && value.persistedStage()
                                    == formalWait.lastStage()),
                    "formal successor wait is not backed by the incomplete transition");
            FormalSuccessorWait reason = formalWait.formalSuccessorWait();
            NextDirective directive = new NextDirective(
                    ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                    reason.sourceAuthorityType(), reason.sourceAuthorityRef());
            return new RecoveryOutcome(
                    RecoveryDisposition.WAITING_FORMAL_SUCCESSOR,
                    afterTransitions, List.copyOf(resumed),
                    new RecoveryResult(List.of(), false), directive);
        }
        require(afterTransitions.taskId().equals(request.taskId())
                        && afterTransitions.incompleteTransitions().isEmpty(),
                "role selection is forbidden while a composite transition is incomplete");

        RecoveryResult inFlight = inFlightActions.recover(
                request.taskId(), request.observedAt());
        Objects.requireNonNull(inFlight, "inFlight recovery result");
        if (inFlight.unresolved()) {
            return new RecoveryOutcome(
                    RecoveryDisposition.WAITING_IN_FLIGHT,
                    afterTransitions, List.copyOf(resumed), inFlight, null);
        }

        RecoverySnapshot finalSnapshot = source.load(request.taskId());
        require(finalSnapshot.taskId().equals(request.taskId())
                        && finalSnapshot.incompleteTransitions().isEmpty(),
                "new incomplete transition appeared before role selection");
        NextDirective directive = roleSelector.select(finalSnapshot);
        Objects.requireNonNull(directive, "next role directive");
        return new RecoveryOutcome(
                RecoveryDisposition.NEXT_ROLE_SELECTED,
                finalSnapshot, List.copyOf(resumed), inFlight, directive);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record RecoveryRequest(String taskId, Instant observedAt) {
        public RecoveryRequest {
            required(taskId, "taskId");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record RecoverySnapshot(
            String taskId,
            List<FactCut> factCuts,
            List<TransitionRef> incompleteTransitions,
            FrozenRoleProjection roleProjection) {
        public RecoverySnapshot {
            required(taskId, "taskId");
            factCuts = List.copyOf(Objects.requireNonNull(factCuts, "factCuts"));
            incompleteTransitions = List.copyOf(Objects.requireNonNull(
                    incompleteTransitions, "incompleteTransitions"));
            Objects.requireNonNull(roleProjection, "roleProjection");
            List<RecoveryFactKind> expected = List.of(RecoveryFactKind.values());
            List<RecoveryFactKind> actual = factCuts.stream().map(FactCut::kind).toList();
            if (!actual.equals(expected)
                    || EnumSet.copyOf(actual).size() != RecoveryFactKind.values().length) {
                throw new IllegalArgumentException(
                        "recovery snapshot must contain the ten fact cuts in frozen order");
            }
            if (incompleteTransitions.stream().anyMatch(value ->
                    !value.taskId().equals(taskId))) {
                throw new IllegalArgumentException(
                        "incomplete transition belongs to another task");
            }
            if (!taskId.equals(roleProjection.taskId())) {
                throw new IllegalArgumentException(
                        "frozen role projection belongs to another task");
            }
            if (roleProjection.authorityCut() < 0) {
                throw new IllegalArgumentException(
                        "frozen role projection authorityCut must be non-negative");
            }
            String boundary = required(
                    roleProjection.readBoundary(), "roleProjection.readBoundary");
            String authorityPrefix = "authority-event-sequence="
                    + roleProjection.authorityCut() + ";";
            if (!boundary.startsWith(authorityPrefix)) {
                throw new IllegalArgumentException(
                        "frozen role projection is not bound to its authority cut");
            }
            if (factCuts.stream().anyMatch(value ->
                    !boundary.equals(value.readBoundary()))) {
                throw new IllegalArgumentException(
                        "fact cuts and frozen role projection must share one read boundary");
            }
        }
    }

    /** Typed state used for role selection and frozen into the same authority cut. */
    public interface FrozenRoleProjection {
        String taskId();

        long authorityCut();

        String readBoundary();
    }

    public record FactCut(
            RecoveryFactKind kind,
            String sourceVersion,
            String readBoundary,
            List<String> authorityRefs) {
        public FactCut {
            Objects.requireNonNull(kind, "kind");
            required(sourceVersion, "sourceVersion");
            required(readBoundary, "readBoundary");
            authorityRefs = List.copyOf(Objects.requireNonNull(
                    authorityRefs, "authorityRefs"));
        }
    }

    public record TransitionRef(
            String transitionId,
            String taskId,
            ChainTransitionType transitionType,
            ChainTransitionStage persistedStage,
            long authoritySequence) {
        public TransitionRef {
            required(transitionId, "transitionId");
            required(taskId, "taskId");
            Objects.requireNonNull(transitionType, "transitionType");
            Objects.requireNonNull(persistedStage, "persistedStage");
            if (!transitionType.accepts(persistedStage)
                    || persistedStage == ChainTransitionStage.COMPLETE) {
                throw new IllegalArgumentException(
                        "incomplete transition stage does not belong to its type");
            }
            if (authoritySequence < 1) {
                throw new IllegalArgumentException("authoritySequence must be positive");
            }
        }
    }

    public record TransitionRecoveryResult(
            String transitionId,
            ChainTransitionType transitionType,
            ChainTransitionStage lastStage,
            TransitionRecoveryDisposition disposition,
            FormalSuccessorWait formalSuccessorWait) {
        public TransitionRecoveryResult(
                String transitionId,
                ChainTransitionType transitionType,
                ChainTransitionStage lastStage) {
            this(transitionId, transitionType, lastStage,
                    TransitionRecoveryDisposition.COMPLETED, null);
        }

        public TransitionRecoveryResult {
            required(transitionId, "transitionId");
            Objects.requireNonNull(transitionType, "transitionType");
            Objects.requireNonNull(lastStage, "lastStage");
            Objects.requireNonNull(disposition, "disposition");
            if (disposition == TransitionRecoveryDisposition.COMPLETED) {
                if (lastStage != ChainTransitionStage.COMPLETE
                        || formalSuccessorWait != null) {
                    throw new IllegalArgumentException(
                            "completed recovery requires COMPLETE and no wait reason");
                }
            } else if (transitionType != ChainTransitionType.FINALIZATION
                    || lastStage
                    != ChainTransitionStage.FINALIZATION_CHECK_COMMITTED
                    || formalSuccessorWait == null) {
                throw new IllegalArgumentException(
                        "formal-successor wait requires a typed finalization failure");
            }
        }

        public static TransitionRecoveryResult waitingForFormalSuccessor(
                String transitionId, FormalSuccessorWait reason) {
            return new TransitionRecoveryResult(
                    transitionId, ChainTransitionType.FINALIZATION,
                    ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                    TransitionRecoveryDisposition.WAITING_FORMAL_SUCCESSOR,
                    Objects.requireNonNull(reason, "reason"));
        }
    }

    public sealed interface FormalSuccessorWait
            permits CheckFailureWait, PublishFailureWait {
        String sourceAuthorityType();

        String sourceAuthorityRef();
    }

    public record CheckFailureWait(
            String finalizationCheckId,
            ChainFinalization.ErrorCode errorCode)
            implements FormalSuccessorWait {
        public CheckFailureWait {
            required(finalizationCheckId, "finalizationCheckId");
            Objects.requireNonNull(errorCode, "errorCode");
        }

        @Override public String sourceAuthorityType() {
            return "FINALIZATION_CHECK";
        }

        @Override public String sourceAuthorityRef() {
            return finalizationCheckId;
        }
    }

    public record PublishFailureWait(
            String finalizationCheckId,
            String formalFailureRef,
            ChainProjectPublishPort.ErrorCode errorCode,
            boolean retryable)
            implements FormalSuccessorWait {
        public PublishFailureWait {
            required(finalizationCheckId, "finalizationCheckId");
            required(formalFailureRef, "formalFailureRef");
            Objects.requireNonNull(errorCode, "errorCode");
        }

        @Override public String sourceAuthorityType() {
            return "PUBLISH_FAILURE";
        }

        @Override public String sourceAuthorityRef() {
            return formalFailureRef;
        }
    }

    public record RecoveryResult(
            List<ActionRecoveryFact> actions,
            boolean unresolved) {
        public RecoveryResult {
            actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
            if (unresolved != actions.stream().anyMatch(ActionRecoveryFact::unresolved)) {
                throw new IllegalArgumentException(
                        "unresolved flag must match recovered actions");
            }
        }
    }

    public record ActionRecoveryFact(
            String actionId,
            String idempotencyKey,
            ChainEffectRuntime.OutcomeKind outcome,
            String receiptRef,
            String errorRef,
            String uncertaintyRef,
            boolean unresolved) {
        public ActionRecoveryFact {
            required(actionId, "actionId");
            required(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == ChainEffectRuntime.OutcomeKind.EFFECT_SUCCEEDED
                    && receiptRef == null) {
                throw new IllegalArgumentException(
                        "successful recovered action requires receiptRef");
            }
            if (outcome == ChainEffectRuntime.OutcomeKind.EFFECT_FAILED
                    && errorRef == null) {
                throw new IllegalArgumentException(
                        "failed recovered action requires errorRef");
            }
            if (outcome == ChainEffectRuntime.OutcomeKind.UNKNOWN_SIDE_EFFECT
                    && (uncertaintyRef == null || receiptRef != null
                    || errorRef != null)) {
                throw new IllegalArgumentException(
                        "unknown recovered action requires uncertainty authority");
            }
            if (outcome != ChainEffectRuntime.OutcomeKind.UNKNOWN_SIDE_EFFECT
                    && uncertaintyRef != null) {
                throw new IllegalArgumentException(
                        "only an unknown side effect carries uncertainty authority");
            }
        }
    }

    public record NextDirective(
            ChainRole role,
            ChainWorkState workState,
            String sourceAuthorityType,
            String sourceAuthorityRef) {
        public NextDirective {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(workState, "workState");
            required(sourceAuthorityType, "sourceAuthorityType");
            required(sourceAuthorityRef, "sourceAuthorityRef");
        }
    }

    public record RecoveryOutcome(
            RecoveryDisposition disposition,
            RecoverySnapshot snapshot,
            List<TransitionRecoveryResult> resumedTransitions,
            RecoveryResult inFlightRecovery,
            NextDirective nextDirective) {
        public RecoveryOutcome {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(snapshot, "snapshot");
            resumedTransitions = List.copyOf(Objects.requireNonNull(
                    resumedTransitions, "resumedTransitions"));
            Objects.requireNonNull(inFlightRecovery, "inFlightRecovery");
            boolean carriesDirective = disposition
                    == RecoveryDisposition.NEXT_ROLE_SELECTED
                    || disposition
                    == RecoveryDisposition.WAITING_FORMAL_SUCCESSOR;
            if (carriesDirective != (nextDirective != null)) {
                throw new IllegalArgumentException(
                        "role selection and formal-successor wait carry directives");
            }
            if (disposition == RecoveryDisposition.WAITING_FORMAL_SUCCESSOR
                    && (nextDirective.role() != ChainRole.REFLECTOR
                    || nextDirective.workState()
                    != ChainWorkState.AWAITING_REVIEW)) {
                throw new IllegalArgumentException(
                        "formal-successor wait requires a Reflector directive");
            }
        }
    }

    public enum RecoveryFactKind {
        INSTRUCTION_AND_PENDING,
        TASKFRAME_PLAN_AND_STEP,
        ACTION_RECEIPT_AND_ERROR,
        CANDIDATE_RESULT_AND_REVIEW,
        WORKSPACE_AND_CANDIDATE,
        REVIEW_READINESS_GAP_AND_TRANSITION,
        PROPOSAL_STATE,
        VALIDATION_FINALIZATION_AND_PUBLISH,
        PAUSE_CANCEL_AND_SUPERSEDE,
        IN_FLIGHT_ACTION
    }

    public enum RecoveryDisposition {
        WAITING_IN_FLIGHT,
        WAITING_FORMAL_SUCCESSOR,
        NEXT_ROLE_SELECTED
    }

    public enum TransitionRecoveryDisposition {
        COMPLETED,
        WAITING_FORMAL_SUCCESSOR
    }

    public interface RecoverySource {
        RecoverySnapshot load(String taskId);
    }

    public interface CompositeTransitionRecovery {
        TransitionRecoveryResult resume(TransitionRef transition);
    }

    public interface InFlightActionRecovery {
        RecoveryResult recover(String taskId, Instant observedAt);
    }

    public interface NextRoleSelector {
        NextDirective select(RecoverySnapshot snapshot);
    }
}
