package com.yanban.api.agent.v2.progression;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import com.yanban.api.agent.v2.result.V2StepResultSource;
import com.yanban.api.agent.v2.result.V2StepResultStatus;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.runtime.execution.activation.composition.ReadyStepActivationCompositionRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCommitted;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.progression.StepProgressionInspector;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Completes a reasoning-only Step from an accepted persisted result. */
@Service
public class AuthenticatedResultDrivenStepProgressionComposer {
    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepProgressionInspector inspector;
    private final StepRecoverer recoverer;
    private final ActiveStepCompletionComposer completion;
    private final StepActivationComposer activation;

    public AuthenticatedResultDrivenStepProgressionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepProgressionInspector inspector,
            StepRecoverer recoverer,
            ActiveStepCompletionComposer completion,
            StepActivationComposer activation) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.planIds = Objects.requireNonNull(planIds, "planIds");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.recoverer = Objects.requireNonNull(recoverer, "recoverer");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.activation = Objects.requireNonNull(activation, "activation");
    }

    public ResultDrivenStepProgressionOutcome complete(
            Long userId, Long turnId,
            PlanId planId, PlanStepId stepId,
            V2StepResultSnapshot result,
            StepRecoveryLeaseAttempt recoveryAttempt,
            EffectDrivenStepProgressionActivationLeaseAttempt
                    activationAttempt) {
        PlanId authoritative = planIds.derive(
                contexts.resolve(userId, turnId).identity());
        require(authoritative.equals(planId), "planId");
        validateResult(planId, stepId, result);
        StepRecoverySnapshot cut = inspect(planId);
        if (!(cut instanceof PersistedStepRecoveryActive current)
                || !current.activation().stepId().equals(stepId)
                || !current.activation().activationEvent().id()
                        .equals(result.activationEventId())) {
            throw rejected("activeStep");
        }
        var recovered = recoverer.recover(
                new StepRecoveryRequest(planId, recoveryAttempt));
        require(recovered instanceof RecoveredActiveStep,
                "recovery.activeStep");
        RecoveredActiveStep active = (RecoveredActiveStep) recovered;
        require(active.leaseDisposition()
                        == StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY
                        && active.recovery().activation().stepId()
                                .equals(stepId)
                        && active.recovery().activation().activationEvent()
                                .id().equals(result.activationEventId()),
                "recovery.authority");
        var composed = completion.compose(
                ResultDrivenStepProgressionDrafts.completion(
                        active, result));
        require(composed instanceof ActiveStepCompletionCommitted,
                "completion.persistence");
        cut = inspect(planId);
        require(completedFromResult(cut, result),
                "completion.result");
        if (cut instanceof PersistedStepRecoverySucceeded) {
            return outcome(planId, stepId, cut,
                    EffectDrivenStepProgressionState.PLAN_SUCCEEDED);
        }
        if (cut instanceof PersistedStepRecoveryActive) {
            return outcome(planId, stepId, cut,
                    EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE);
        }
        require(cut instanceof PersistedStepRecoveryReady,
                "progression.ready");
        PersistedStepRecoveryReady ready =
                (PersistedStepRecoveryReady) cut;
        var activated = activation.composeReady(
                new ReadyStepActivationCompositionRequest(
                        ready,
                        ResultDrivenStepProgressionDrafts.activation(
                                ready, result, activationAttempt)));
        require(activated instanceof StepActivationCommitted,
                "activation.persistence");
        StepRecoverySnapshot finalCut = inspect(planId);
        require(finalCut instanceof PersistedStepRecoveryActive
                        && completedFromResult(finalCut, result),
                "activation.authority");
        return outcome(planId, stepId, finalCut,
                EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE);
    }

    private StepRecoverySnapshot inspect(PlanId planId) {
        var inspected = inspector.inspect(planId);
        if (inspected == null
                || inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || inspected.value().isEmpty()) {
            throw rejected("inspection");
        }
        return inspected.value().orElseThrow();
    }

    private static void validateResult(
            PlanId planId, PlanStepId stepId,
            V2StepResultSnapshot result) {
        require(result != null
                        && result.status()
                                == V2StepResultStatus.ACCEPTED
                        && result.source() == V2StepResultSource.MODEL
                        && result.planId().equals(planId)
                        && result.stepId().equals(stepId)
                        && result.acceptedText().isPresent()
                        && result.acceptedSha256().isPresent()
                        && result.evidenceReceiptIds().isEmpty(),
                "acceptedResult");
    }

    private static boolean completedFromResult(
            StepRecoverySnapshot cut,
            V2StepResultSnapshot result) {
        io.paperagent.v2.contracts.Plan plan;
        if (cut instanceof PersistedStepRecoveryActive value) {
            plan = value.plan();
        } else if (cut instanceof PersistedStepRecoveryReady value) {
            plan = value.plan();
        } else if (cut instanceof PersistedStepRecoverySucceeded value) {
            plan = value.plan();
        } else {
            return false;
        }
        CompletionFact fact = plan.latestRevision().completedFacts()
                .get(result.stepId());
        return fact != null
                && fact.outcomeHash().equals(
                        "sha256." + result.acceptedSha256().orElseThrow())
                && fact.receiptReferences().isEmpty();
    }

    private static ResultDrivenStepProgressionOutcome outcome(
            PlanId planId, PlanStepId stepId,
            StepRecoverySnapshot cut,
            EffectDrivenStepProgressionState state) {
        return new ResultDrivenStepProgressionOutcome(
                planId, stepId, state, cut);
    }

    private static void require(boolean condition, String path) {
        if (!condition) {
            throw rejected(path);
        }
    }

    private static IllegalStateException rejected(String path) {
        return new IllegalStateException(
                "result-driven progression rejected at " + path);
    }
}
