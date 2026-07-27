package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

/**
 * Provider-neutral one-turn handoff from a recovered active Step to durable intent.
 *
 * <p>This kernel does not manage the recovered lease or execute the effect it records.
 */
public final class DefaultSingleTurnStepKernel implements SingleTurnStepKernel {
    private final StepTurnPort stepTurnPort;
    private final EffectIntentRepository effectIntentRepository;

    public DefaultSingleTurnStepKernel(
            StepTurnPort stepTurnPort,
            EffectIntentRepository effectIntentRepository) {
        this.stepTurnPort = SingleTurnStepKernelValues.required(
                stepTurnPort, "singleTurnStepKernel.stepTurnPort");
        this.effectIntentRepository = SingleTurnStepKernelValues.required(
                effectIntentRepository, "singleTurnStepKernel.effectIntentRepository");
    }

    @Override
    public SingleTurnStepKernelOutcome run(SingleTurnStepKernelRequest request) {
        SingleTurnStepKernelRequest requiredRequest = SingleTurnStepKernelValues.required(
                request, "singleTurnStepKernel.request");
        Authority authority = authority(requiredRequest.recoveredStep());
        StepTurnDecision decision = decide(authority);
        if (decision instanceof NoEffectDecision) {
            return new SingleTurnNoEffect(authority.planId(), authority.stepId());
        }
        if (decision instanceof EffectIntentDecision effectIntentDecision) {
            return persistIntent(authority, effectIntentDecision.intent());
        }
        throw protocol(
                authority,
                SingleTurnStepKernelStage.TURN_DECISION,
                SingleTurnStepKernelProtocolCode.UNKNOWN_TURN_DECISION,
                "singleTurnStepKernel.turnDecision",
                null);
    }

    private StepTurnDecision decide(Authority authority) {
        StepTurnDecision decision;
        try {
            decision = stepTurnPort.decide(new StepTurnInput(
                    authority.recovery().taskFrame(),
                    authority.plan(),
                    authority.checkpoint(),
                    authority.activeStep()));
        } catch (RuntimeException exception) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.TURN_DECISION,
                    SingleTurnStepKernelProtocolCode.COLLABORATOR_EXCEPTION,
                    "singleTurnStepKernel.turnDecision",
                    exception);
        }
        if (decision == null) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.TURN_DECISION,
                    SingleTurnStepKernelProtocolCode.NULL_COLLABORATOR_RESULT,
                    "singleTurnStepKernel.turnDecision",
                    null);
        }
        return decision;
    }

    private SingleTurnStepKernelOutcome persistIntent(
            Authority authority,
            EffectIntent intent) {
        if (!authority.planId().equals(intent.planId())
                || !authority.stepId().equals(intent.stepId())) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.TURN_DECISION,
                    SingleTurnStepKernelProtocolCode.INCONSISTENT_DECISION_AUTHORITY,
                    "singleTurnStepKernel.turnDecision",
                    null);
        }
        EffectIntentRequest request = new EffectIntentRequest(
                intent,
                authority.lease().leaseToken(),
                authority.lease().fencingToken(),
                authority.activation().activationEvent().id());
        PersistenceResult<PersistedEffectIntent> result;
        try {
            result = effectIntentRepository.persist(request);
        } catch (RuntimeException exception) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.INTENT_PERSISTENCE,
                    SingleTurnStepKernelProtocolCode.COLLABORATOR_EXCEPTION,
                    "singleTurnStepKernel.intentPersistResult",
                    exception);
        }
        if (result == null) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.INTENT_PERSISTENCE,
                    SingleTurnStepKernelProtocolCode.NULL_COLLABORATOR_RESULT,
                    "singleTurnStepKernel.intentPersistResult",
                    null);
        }
        if (result.outcome() == null) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.INTENT_PERSISTENCE,
                    SingleTurnStepKernelProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "singleTurnStepKernel.intentPersistResult.outcome",
                    null);
        }
        return switch (result.outcome()) {
            case APPLIED, REPLAYED -> persisted(authority, intent, result);
            case REJECTED -> rejected(authority, result);
            case FOUND -> throw protocol(
                    authority,
                    SingleTurnStepKernelStage.INTENT_PERSISTENCE,
                    SingleTurnStepKernelProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "singleTurnStepKernel.intentPersistResult.outcome",
                    null);
        };
    }

    private static SingleTurnIntentPersisted persisted(
            Authority authority,
            EffectIntent intent,
            PersistenceResult<PersistedEffectIntent> result) {
        PersistedEffectIntent persistedIntent = result.value().orElse(null);
        if (persistedIntent == null
                || !intent.equals(persistedIntent.intent())
                || !authority.lease().ownerId().equals(persistedIntent.leaseOwnerId())
                || authority.lease().fencingToken() != persistedIntent.fencingToken()
                || !authority.activation().activationEvent().id().equals(
                        persistedIntent.activationEventId())) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.INTENT_PERSISTENCE,
                    SingleTurnStepKernelProtocolCode.INCONSISTENT_PERSISTED_INTENT,
                    "singleTurnStepKernel.intentPersistResult.value",
                    null);
        }
        return new SingleTurnIntentPersisted(persistedIntent);
    }

    private static SingleTurnPersistenceRejected rejected(
            Authority authority,
            PersistenceResult<PersistedEffectIntent> result) {
        PersistenceFailure failure = result.failure().orElse(null);
        if (failure == null) {
            throw protocol(
                    authority,
                    SingleTurnStepKernelStage.INTENT_PERSISTENCE,
                    SingleTurnStepKernelProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "singleTurnStepKernel.intentPersistResult.failure",
                    null);
        }
        return new SingleTurnPersistenceRejected(
                authority.planId(), authority.stepId(), failure);
    }

    private static Authority authority(RecoveredActiveStep recoveredStep) {
        PersistedStepRecoveryActive recovery = recoveredStep.recovery();
        Plan plan = recovery.plan();
        VersionedCheckpoint checkpoint = recovery.checkpoint();
        PersistedStepActivation activation = recovery.activation();
        LeaseRecord lease = recoveredStep.lease();
        PlanId planId = plan.id();
        PlanStepId stepId = activation.stepId();

        if (!planId.equals(recoveredStep.planId())
                || !planId.equals(checkpoint.checkpoint().planId())
                || !recovery.taskFrame().id().equals(plan.taskFrameId())
                || !recovery.taskFrame().id().equals(
                        checkpoint.checkpoint().taskFrameId())
                || !planId.equals(activation.planId())
                || !planId.equals(activation.activationEvent().planId())
                || !recovery.taskFrame().id().equals(
                        activation.activationEvent().taskFrameId())
                || !checkpoint.equals(activation.activatedCheckpoint())
                || !planId.equals(lease.planId())
                || !lease.ownerId().equals(activation.leaseOwnerId())
                || lease.fencingToken() != activation.fencingToken()) {
            throw protocol(
                    planId,
                    stepId,
                    SingleTurnStepKernelStage.RECOVERED_AUTHORITY,
                    SingleTurnStepKernelProtocolCode.INCONSISTENT_RECOVERED_AUTHORITY,
                    "singleTurnStepKernel.recoveredAuthority",
                    null);
        }

        PlanRevision currentRevision = plan.latestRevision();
        if (!currentRevision.id().equals(checkpoint.checkpoint().revisionId())
                || currentRevision.number() != checkpoint.checkpoint().revisionNumber()
                || checkpoint.checkpoint().planState() != PlanExecutionState.ACTIVE
                || checkpoint.checkpoint().stepStates().get(stepId)
                        != StepExecutionState.ACTIVE) {
            throw protocol(
                    planId,
                    stepId,
                    SingleTurnStepKernelStage.RECOVERED_AUTHORITY,
                    SingleTurnStepKernelProtocolCode.INCONSISTENT_RECOVERED_AUTHORITY,
                    "singleTurnStepKernel.recoveredAuthority",
                    null);
        }
        PlanStep activeStep = currentRevision.steps().stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst()
                .orElse(null);
        if (activeStep == null) {
            throw protocol(
                    planId,
                    stepId,
                    SingleTurnStepKernelStage.RECOVERED_AUTHORITY,
                    SingleTurnStepKernelProtocolCode.INCONSISTENT_RECOVERED_AUTHORITY,
                    "singleTurnStepKernel.recoveredAuthority",
                    null);
        }
        return new Authority(recovery, plan, checkpoint, activation, lease, activeStep);
    }

    private static SingleTurnStepKernelProtocolException protocol(
            Authority authority,
            SingleTurnStepKernelStage stage,
            SingleTurnStepKernelProtocolCode code,
            String path,
            Throwable cause) {
        return protocol(authority.planId(), authority.stepId(), stage, code, path, cause);
    }

    private static SingleTurnStepKernelProtocolException protocol(
            PlanId planId,
            PlanStepId stepId,
            SingleTurnStepKernelStage stage,
            SingleTurnStepKernelProtocolCode code,
            String path,
            Throwable cause) {
        return SingleTurnStepKernelValues.protocolFailure(
                planId, stepId, stage, code, path, cause);
    }

    private record Authority(
            PersistedStepRecoveryActive recovery,
            Plan plan,
            VersionedCheckpoint checkpoint,
            PersistedStepActivation activation,
            LeaseRecord lease,
            PlanStep activeStep) {
        private PlanId planId() {
            return plan.id();
        }

        private PlanStepId stepId() {
            return activeStep.id();
        }
    }
}
