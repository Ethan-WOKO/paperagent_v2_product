package io.paperagent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reconciles the original action identity before any same-action continuation. */
public final class ChainInFlightActionRecovery
        implements ChainRecoveryRuntime.InFlightActionRecovery {
    private final ChainWorkflowRepository workflow;
    private final ChainEffectRuntime effects;

    public ChainInFlightActionRecovery(
            ChainWorkflowRepository workflow,
            ChainEffectRuntime effects) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    @Override
    public ChainRecoveryRuntime.RecoveryResult recover(
            String taskId, Instant observedAt) {
        required(taskId, "taskId");
        Objects.requireNonNull(observedAt, "observedAt");
        List<ChainPersistenceRecords.ActionBindingRecord> inFlight = workflow
                .findInFlightActions(taskId).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.ActionBindingRecord::attemptNo)
                        .thenComparing(ChainPersistenceRecords.ActionBindingRecord::actionId))
                .toList();
        Set<String> actionIds = new HashSet<>();
        Set<String> idempotencyKeys = new HashSet<>();
        for (ChainPersistenceRecords.ActionBindingRecord action : inFlight) {
            if (!action.taskId().equals(taskId)
                    || !actionIds.add(action.actionId())
                    || !idempotencyKeys.add(action.idempotencyKey())) {
                throw new IllegalStateException(
                        "in-flight actions must have unique formal identities for one task");
            }
        }
        List<ChainRecoveryRuntime.ActionRecoveryFact> recovered = inFlight.stream().map(action -> {
            ChainEffectRuntime.ExecutionOutcome outcome = effects.recoverBoundAction(
                    new ChainEffectRuntime.ActionRecoveryRequest(
                            taskId, action.actionId(), observedAt));
            if (!outcome.action().actionId().equals(action.actionId())
                    || !outcome.action().idempotencyKey().equals(action.idempotencyKey())) {
                throw new IllegalStateException(
                        "in-flight reconciliation changed action identity");
            }
            return new ChainRecoveryRuntime.ActionRecoveryFact(
                    action.actionId(), action.idempotencyKey(), outcome.kind(),
                    outcome.receiptRef(), outcome.errorRef(),
                    outcome.uncertaintyRef(),
                    unresolved(outcome.kind()));
        }).toList();
        return new ChainRecoveryRuntime.RecoveryResult(
                recovered,
                recovered.stream().anyMatch(
                        ChainRecoveryRuntime.ActionRecoveryFact::unresolved));
    }

    private static boolean unresolved(ChainEffectRuntime.OutcomeKind kind) {
        return kind == ChainEffectRuntime.OutcomeKind.WAITING_EFFECT
                || kind == ChainEffectRuntime.OutcomeKind.UNKNOWN_SIDE_EFFECT
                || kind == ChainEffectRuntime.OutcomeKind.NOT_DISPATCHED;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

}
