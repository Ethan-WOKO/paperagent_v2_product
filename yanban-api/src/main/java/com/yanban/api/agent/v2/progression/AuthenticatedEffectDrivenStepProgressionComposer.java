package com.yanban.api.agent.v2.progression;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.runtime.execution.activation.composition.ReadyStepActivationCompositionRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionOutcome;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCommitted;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCompositionOutcome;
import io.paperagent.v2.runtime.execution.progression.StepProgressionInspector;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Authenticated composition from one successful persisted effect to either a
 * next ACTIVE Step or a terminal successful Plan.
 *
 * <p>Completion and next activation remain separate durable commits. Every
 * phase is followed by a fresh inspection so a restart or concurrent winner
 * is classified only from current persistence authority.
 */
@Service
public class AuthenticatedEffectDrivenStepProgressionComposer {
    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepProgressionInspector inspector;
    private final StepRecoverer recoverer;
    private final EffectIntentRepository intents;
    private final EffectOutcomeRepository outcomes;
    private final ActiveStepCompletionComposer completion;
    private final StepActivationComposer activation;

    public AuthenticatedEffectDrivenStepProgressionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepProgressionInspector inspector,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ActiveStepCompletionComposer completion,
            StepActivationComposer activation) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.planIds = Objects.requireNonNull(planIds, "planIds");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.recoverer = Objects.requireNonNull(recoverer, "recoverer");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.activation = Objects.requireNonNull(activation, "activation");
    }

    public EffectDrivenStepProgressionOutcome progress(
            Long userId, Long agentTurnId,
            EffectDrivenStepProgressionCommand command) {
        Objects.requireNonNull(command, "command");
        PlanId authoritativePlan = planIds.derive(
                contexts.resolve(userId, agentTurnId).identity());
        if (!authoritativePlan.equals(command.planId())) {
            throw rejected("command.planId");
        }

        PersistedEffectIntent intent = loadIntent(command);
        PersistedEffectResult result = loadResult(command);
        ExecutionReceipt receipt = validateEvidence(
                authoritativePlan, command, intent, result);

        Optional<PersistenceOutcome> completionOutcome = Optional.empty();
        StepRecoverySnapshot cut = inspect(authoritativePlan);
        if (cut instanceof PersistedStepRecoveryActive active
                && sameActivation(active, intent)) {
            RecoveredActiveStep recovered = recoverOrNull(
                    authoritativePlan, command, intent);
            if (recovered == null) {
                cut = inspect(authoritativePlan);
                if (!completedFromExactReceipt(cut, intent, receipt)) {
                    throw rejected("recovery.activeStep");
                }
            } else {
                ActiveStepCompletionCompositionOutcome composed =
                        completion.compose(
                                EffectDrivenStepProgressionDrafts.completion(
                                        recovered, intent, receipt));
                if (!(composed
                                instanceof ActiveStepCompletionCommitted
                                        committed)
                        || !committed.planId().equals(authoritativePlan)
                        || !committed.stepId().equals(
                                intent.intent().stepId())) {
                    cut = inspect(authoritativePlan);
                    if (!completedFromExactReceipt(cut, intent, receipt)) {
                        throw rejected("completion.persistence");
                    }
                } else {
                    completionOutcome =
                            Optional.of(committed.persistenceOutcome());
                    validateCommittedCompletion(committed, intent, receipt);
                    cut = inspect(authoritativePlan);
                }
            }
        }

        if (!completedFromExactReceipt(cut, intent, receipt)) {
            throw rejected("completion.evidence");
        }
        if (cut instanceof PersistedStepRecoverySucceeded) {
            return outcome(
                    cut, intent, receipt,
                    EffectDrivenStepProgressionState.PLAN_SUCCEEDED,
                    completionOutcome, Optional.empty());
        }
        if (cut instanceof PersistedStepRecoveryActive active) {
            validateNextActive(active, intent, receipt);
            return outcome(
                    cut, intent, receipt,
                    EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                    completionOutcome, Optional.empty());
        }
        if (!(cut instanceof PersistedStepRecoveryReady ready)) {
            throw rejected("progression.afterCompletion");
        }

        StepActivationCompositionOutcome activated = activation.composeReady(
                new ReadyStepActivationCompositionRequest(
                        ready,
                        EffectDrivenStepProgressionDrafts.activation(
                                ready, intent, receipt,
                                command.nextStepActivationAttempt())));
        Optional<PersistenceOutcome> activationOutcome = Optional.empty();
        if (activated instanceof StepActivationCommitted committed) {
            if (!committed.planId().equals(authoritativePlan)
                    || !committed.persistedActivation().stepId()
                            .equals(ready.readyStepId())) {
                throw rejected("activation.authority");
            }
            activationOutcome =
                    Optional.of(committed.activationOutcome());
        }
        StepRecoverySnapshot finalCut = inspect(authoritativePlan);
        if (!(finalCut instanceof PersistedStepRecoveryActive finalActive)) {
            throw rejected("activation.persistence");
        }
        validateNextActive(finalActive, intent, receipt);
        return outcome(
                finalCut, intent, receipt,
                EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                completionOutcome, activationOutcome);
    }

    private PersistedEffectIntent loadIntent(
            EffectDrivenStepProgressionCommand command) {
        PersistenceResult<PersistedEffectIntent> found =
                intents.find(command.toolCallId());
        if (found == null
                || found.outcome() != PersistenceOutcome.FOUND
                || found.failure().isPresent()
                || found.value().isEmpty()) {
            throw rejected("effectIntent");
        }
        return found.value().orElseThrow();
    }

    private PersistedEffectResult loadResult(
            EffectDrivenStepProgressionCommand command) {
        PersistenceResult<PersistedEffectResult> found =
                outcomes.findResult(command.toolCallId());
        if (found == null
                || found.outcome() != PersistenceOutcome.FOUND
                || found.failure().isPresent()
                || found.value().isEmpty()) {
            throw rejected("effectOutcome");
        }
        return found.value().orElseThrow();
    }

    private static ExecutionReceipt validateEvidence(
            PlanId planId,
            EffectDrivenStepProgressionCommand command,
            PersistedEffectIntent intent,
            PersistedEffectResult result) {
        ExecutionReceipt receipt = result.receipt();
        if (!intent.intent().toolCallId().equals(command.toolCallId())) {
            throw rejected("effect.intent_call");
        }
        if (!receipt.toolCallId().equals(command.toolCallId())) {
            throw rejected("effect.receipt_call");
        }
        if (!intent.intent().planId().equals(planId)
                || !intent.intent().planId().equals(command.planId())) {
            throw rejected("effect.plan");
        }
        if (!intent.leaseOwnerId().equals(result.leaseOwnerId())) {
            throw rejected("effect.lease_owner");
        }
        if (intent.fencingToken() != result.fencingToken()) {
            throw rejected("effect.fence");
        }
        if (receipt.status() != ReceiptStatus.SUCCESS) {
            throw rejected("effect.receipt_status");
        }
        return receipt;
    }

    private RecoveredActiveStep recoverOrNull(
            PlanId planId,
            EffectDrivenStepProgressionCommand command,
            PersistedEffectIntent intent) {
        StepRecoveryCompositionOutcome recovered = recoverer.recover(
                new StepRecoveryRequest(
                        planId, command.currentStepRecoveryAttempt()));
        if (!(recovered instanceof RecoveredActiveStep active)
                || active.leaseDisposition()
                        != StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY
                || !active.planId().equals(planId)
                || !sameActivation(active.recovery(), intent)) {
            return null;
        }
        return active;
    }

    private StepRecoverySnapshot inspect(PlanId planId) {
        PersistenceResult<StepRecoverySnapshot> result =
                inspector.inspect(planId);
        if (result == null
                || result.outcome() != PersistenceOutcome.FOUND
                || result.failure().isPresent()
                || result.value().isEmpty()
                || !result.value().orElseThrow().planId().equals(planId)) {
            throw rejected("progression.inspection");
        }
        return result.value().orElseThrow();
    }

    private static boolean sameActivation(
            PersistedStepRecoveryActive active,
            PersistedEffectIntent intent) {
        return active.planId().equals(intent.intent().planId())
                && active.activation().stepId()
                        .equals(intent.intent().stepId())
                && active.activation().activationEvent().id()
                        .equals(intent.activationEventId())
                && active.activation().leaseOwnerId()
                        .equals(intent.leaseOwnerId())
                && active.activation().fencingToken()
                        == intent.fencingToken();
    }

    private static boolean completedFromExactReceipt(
            StepRecoverySnapshot cut,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt) {
        var checkpoint = checkpoint(cut);
        if (!cut.planId().equals(intent.intent().planId())
                || checkpoint.stepStates().get(intent.intent().stepId())
                        != StepExecutionState.SUCCEEDED
                || checkpoint.receiptReferences().stream()
                        .filter(receipt.id()::equals).count() != 1) {
            return false;
        }
        CompletionFact fact = plan(cut).latestRevision().completedFacts()
                .get(intent.intent().stepId());
        return fact != null
                && fact.stepId().equals(intent.intent().stepId())
                && fact.completedAt().equals(receipt.endedAt())
                && fact.receiptReferences().equals(List.of(receipt.id()))
                && fact.outcomeHash().equals(
                        EffectDrivenStepProgressionDrafts.receiptHash(
                                intent, receipt));
    }

    private static void validateCommittedCompletion(
            ActiveStepCompletionCommitted committed,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt) {
        var persisted = committed.persistedCompletion();
        CompletionFact fact = persisted.completedRevision().completedFacts()
                .get(intent.intent().stepId());
        if (!persisted.planId().equals(intent.intent().planId())
                || !persisted.stepId().equals(intent.intent().stepId())
                || !persisted.completionEvent().id().equals(
                        EffectDrivenStepProgressionDrafts.completionEventId(
                                intent, receipt))
                || fact == null
                || !fact.receiptReferences()
                        .equals(List.of(receipt.id()))) {
            throw rejected("completion.authority");
        }
    }

    private static void validateNextActive(
            PersistedStepRecoveryActive active,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt) {
        EventId expectedCause =
                EffectDrivenStepProgressionDrafts.completionEventId(
                        intent, receipt);
        if (active.activation().stepId().equals(intent.intent().stepId())
                || !active.activation().activationEvent().id().equals(
                        EffectDrivenStepProgressionDrafts
                                .nextActivationEventId(
                                        active.activation().stepId(),
                                        intent, receipt))
                || active.activation().activationEvent().causationId()
                        .filter(expectedCause::equals).isEmpty()
                || !completedFromExactReceipt(active, intent, receipt)) {
            throw rejected("progression.nextActive");
        }
    }

    private static EffectDrivenStepProgressionOutcome outcome(
            StepRecoverySnapshot cut,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt,
            EffectDrivenStepProgressionState state,
            Optional<PersistenceOutcome> completionOutcome,
            Optional<PersistenceOutcome> activationOutcome) {
        return new EffectDrivenStepProgressionOutcome(
                cut.planId(), intent.intent().stepId(), receipt.id(), state,
                completionOutcome, activationOutcome, cut);
    }

    private static io.paperagent.v2.contracts.Plan plan(
            StepRecoverySnapshot cut) {
        if (cut instanceof PersistedStepRecoveryActive active) {
            return active.plan();
        }
        if (cut instanceof PersistedStepRecoveryReady ready) {
            return ready.plan();
        }
        return ((PersistedStepRecoverySucceeded) cut).plan();
    }

    private static io.paperagent.v2.contracts.Checkpoint checkpoint(
            StepRecoverySnapshot cut) {
        if (cut instanceof PersistedStepRecoveryActive active) {
            return active.checkpoint().checkpoint();
        }
        if (cut instanceof PersistedStepRecoveryReady ready) {
            return ready.checkpoint().checkpoint();
        }
        return ((PersistedStepRecoverySucceeded) cut)
                .checkpoint().checkpoint();
    }

    private static EffectDrivenStepProgressionException rejected(
            String path) {
        return new EffectDrivenStepProgressionException(path);
    }
}
