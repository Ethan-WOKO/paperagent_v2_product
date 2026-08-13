package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.finalization.ChainFinalizationRuntime;
import io.paperagent.v2.chain.recovery.ChainInFlightActionRecovery;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Transition-first recovery with typed mechanical finalization and waits. */
public final class ProductChainRecoveryCoordinator {
    private final ChainRecoveryRuntime recovery;
    private final ProductChainMechanicalFinalizationPort finalization;

    public ProductChainRecoveryCoordinator(
            ProductChainRecoverySource source,
            ProductChainCompositeTransitionRecovery transitions,
            ChainWorkflowRepository workflow,
            ChainEffectRuntime effects,
            ProductChainNextRoleSelector roles,
            ProductChainMechanicalFinalizationPort finalization) {
        this.recovery = new ChainRecoveryRuntime(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(transitions, "transitions"),
                new ChainInFlightActionRecovery(
                        Objects.requireNonNull(workflow, "workflow"),
                        Objects.requireNonNull(effects, "effects")),
                Objects.requireNonNull(roles, "roles"));
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
    }

    public RecoveryResult recover(String taskId, Instant observedAt) {
        ChainRecoveryRuntime.RecoveryRequest request =
                new ChainRecoveryRuntime.RecoveryRequest(taskId, observedAt);
        try {
            return new RuntimeOutcome(recovery.recover(request),
                    Optional.empty(), Optional.empty());
        } catch (ProductChainNextRoleSelector.NonModelSelection selected) {
            if (!(selected.selection()
                    instanceof ProductChainNextRoleSelector
                    .MechanicalFinalization directive)) {
                return new Waiting(selected.snapshot(),
                        selected.selection(), Optional.empty(),
                        Optional.empty());
            }
            ChainFinalizationRuntime.Result finalized = Objects.requireNonNull(
                    finalization.finalizeReadiness(
                            directive.readinessId(), observedAt),
                    "mechanical finalization result");
            try {
                return new RuntimeOutcome(recovery.recover(request),
                        Optional.of(finalized), Optional.of(directive));
            } catch (ProductChainNextRoleSelector.NonModelSelection after) {
                if (after.selection()
                        instanceof ProductChainNextRoleSelector
                        .MechanicalFinalization repeated
                        && repeated.readinessId().equals(
                        directive.readinessId())) {
                    throw new IllegalStateException(
                            "mechanical finalization made no formal progress");
                }
                return new Waiting(after.snapshot(), after.selection(),
                        Optional.of(finalized), Optional.of(directive));
            }
        }
    }

    public sealed interface RecoveryResult permits RuntimeOutcome, Waiting {
        ChainRecoveryRuntime.RecoverySnapshot snapshot();
    }

    public record RuntimeOutcome(
            ChainRecoveryRuntime.RecoveryOutcome outcome,
            Optional<ChainFinalizationRuntime.Result> finalizationResult,
            Optional<ProductChainNextRoleSelector.MechanicalFinalization>
                    completedMechanicalSelection)
            implements RecoveryResult {
        public RuntimeOutcome {
            Objects.requireNonNull(outcome, "outcome");
            finalizationResult = Objects.requireNonNull(
                    finalizationResult, "finalizationResult");
            completedMechanicalSelection = Objects.requireNonNull(
                    completedMechanicalSelection,
                    "completedMechanicalSelection");
            if (finalizationResult.isPresent()
                    != completedMechanicalSelection.isPresent()) {
                throw new IllegalArgumentException(
                        "mechanical finalization result must retain its selected identity");
            }
            if (finalizationResult.isPresent()
                    && !completedMechanicalSelection.orElseThrow()
                    .readinessId().equals(readinessId(
                            finalizationResult.orElseThrow()))) {
                throw new IllegalArgumentException(
                        "mechanical finalization result changed its selected readiness");
            }
        }

        @Override
        public ChainRecoveryRuntime.RecoverySnapshot snapshot() {
            return outcome.snapshot();
        }
    }

    public record Waiting(
            ChainRecoveryRuntime.RecoverySnapshot snapshot,
            ProductChainNextRoleSelector.Selection directive,
            Optional<ChainFinalizationRuntime.Result> finalizationResult,
            Optional<ProductChainNextRoleSelector.MechanicalFinalization>
                    completedMechanicalSelection)
            implements RecoveryResult {
        public Waiting {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(directive, "directive");
            if (directive instanceof ProductChainNextRoleSelector.Model) {
                throw new IllegalArgumentException(
                        "a model directive is not a mechanical wait");
            }
            finalizationResult = Objects.requireNonNull(
                    finalizationResult, "finalizationResult");
            completedMechanicalSelection = Objects.requireNonNull(
                    completedMechanicalSelection,
                    "completedMechanicalSelection");
            if (finalizationResult.isPresent()
                    != completedMechanicalSelection.isPresent()) {
                throw new IllegalArgumentException(
                        "mechanical finalization result must retain its selected identity");
            }
            if (finalizationResult.isPresent()
                    && !completedMechanicalSelection.orElseThrow()
                    .readinessId().equals(readinessId(
                            finalizationResult.orElseThrow()))) {
                throw new IllegalArgumentException(
                        "mechanical finalization result changed its selected readiness");
            }
        }
    }

    private static String readinessId(
            ChainFinalizationRuntime.Result result) {
        if (result instanceof ChainFinalizationRuntime.Completed completed) {
            return completed.check().readinessId();
        }
        if (result instanceof ChainFinalizationRuntime.CheckFailed failed) {
            return failed.check().readinessId();
        }
        return ((ChainFinalizationRuntime.PublishFailed) result)
                .check().readinessId();
    }
}
