package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.ActiveStepReplanRepository;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopTurnLimitReached;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

/** Composes one validated turn-limit handoff to fenced active-Step replan persistence. */
public final class DefaultBoundedStepReplanComposer
        implements BoundedStepReplanComposer {
    private final ActiveStepReplanRepository activeStepReplanRepository;

    public DefaultBoundedStepReplanComposer(
            ActiveStepReplanRepository activeStepReplanRepository) {
        this.activeStepReplanRepository = BoundedStepReplanCompositionValues.required(
                activeStepReplanRepository,
                "boundedStepReplanComposition.activeStepReplanRepository");
    }

    @Override
    public BoundedStepReplanCompositionOutcome compose(
            RecoveredActiveStep recoveredActiveStep,
            BoundedStepAgentLoopTurnLimitReached turnLimitReached,
            ActiveStepReplanRequest activeStepReplanRequest) {
        RecoveredActiveStep recovered = BoundedStepReplanCompositionValues.required(
                recoveredActiveStep,
                "boundedStepReplanComposition.recoveredActiveStep");
        BoundedStepAgentLoopTurnLimitReached limit =
                BoundedStepReplanCompositionValues.required(
                        turnLimitReached,
                        "boundedStepReplanComposition.turnLimitReached");
        ActiveStepReplanRequest request = BoundedStepReplanCompositionValues.required(
                activeStepReplanRequest,
                "boundedStepReplanComposition.activeStepReplanRequest");
        PlanId planId = recovered.planId();
        PlanStepId stepId = recovered.recovery().activation().stepId();

        BoundedStepReplanCompositionValues.requireAuthority(recovered, limit, request);
        return replan(planId, stepId, recovered, request);
    }

    private BoundedStepReplanCompositionOutcome replan(
            PlanId planId,
            PlanStepId stepId,
            RecoveredActiveStep recovered,
            ActiveStepReplanRequest request) {
        PersistenceResult<PersistedActiveStepReplan> result;
        try {
            result = activeStepReplanRepository.supersedeAndReplan(request);
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    stepId,
                    BoundedStepReplanCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                    "boundedStepReplanComposition.replanResult",
                    exception);
        }
        if (result == null) {
            throw protocol(
                    planId,
                    stepId,
                    BoundedStepReplanCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                    "boundedStepReplanComposition.replanResult",
                    null);
        }
        if (result.outcome() == null) {
            throw protocol(
                    planId,
                    stepId,
                    BoundedStepReplanCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "boundedStepReplanComposition.replanResult.outcome",
                    null);
        }
        return switch (result.outcome()) {
            case APPLIED -> applied(planId, stepId, recovered, request, result);
            case REPLAYED -> replayed(planId, stepId, recovered, request, result);
            case REJECTED -> rejected(planId, stepId, result);
            case FOUND -> throw protocol(
                    planId,
                    stepId,
                    BoundedStepReplanCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "boundedStepReplanComposition.replanResult.outcome",
                    null);
        };
    }

    private static BoundedStepReplanApplied applied(
            PlanId planId,
            PlanStepId stepId,
            RecoveredActiveStep recovered,
            ActiveStepReplanRequest request,
            PersistenceResult<PersistedActiveStepReplan> result) {
        return new BoundedStepReplanApplied(success(
                planId, stepId, recovered, request, result));
    }

    private static BoundedStepReplanReplayed replayed(
            PlanId planId,
            PlanStepId stepId,
            RecoveredActiveStep recovered,
            ActiveStepReplanRequest request,
            PersistenceResult<PersistedActiveStepReplan> result) {
        return new BoundedStepReplanReplayed(success(
                planId, stepId, recovered, request, result));
    }

    private static PersistedActiveStepReplan success(
            PlanId planId,
            PlanStepId stepId,
            RecoveredActiveStep recovered,
            ActiveStepReplanRequest request,
            PersistenceResult<PersistedActiveStepReplan> result) {
        Object value = result.value() == null ? null : result.value().orElse(null);
        if (!(value instanceof PersistedActiveStepReplan persisted)
                || !BoundedStepReplanCompositionValues.matchesRequest(
                        persisted, request, recovered.lease().ownerId())) {
            throw protocol(
                    planId,
                    stepId,
                    BoundedStepReplanCompositionProtocolCode.INCONSISTENT_REPLAN_RESULT,
                    "boundedStepReplanComposition.replanResult.value",
                    null);
        }
        return persisted;
    }

    private static BoundedStepReplanPersistenceRejected rejected(
            PlanId planId,
            PlanStepId stepId,
            PersistenceResult<PersistedActiveStepReplan> result) {
        Object failureValue = result.failure() == null
                ? null : result.failure().orElse(null);
        if (!(failureValue instanceof PersistenceFailure failure)) {
            throw protocol(
                    planId,
                    stepId,
                    BoundedStepReplanCompositionProtocolCode.INCONSISTENT_REPLAN_RESULT,
                    "boundedStepReplanComposition.replanResult.failure",
                    null);
        }
        return new BoundedStepReplanPersistenceRejected(planId, failure);
    }

    private static BoundedStepReplanCompositionProtocolException protocol(
            PlanId planId,
            PlanStepId stepId,
            BoundedStepReplanCompositionProtocolCode code,
            String path,
            Throwable cause) {
        return BoundedStepReplanCompositionValues.protocolFailure(
                planId,
                stepId,
                BoundedStepReplanCompositionStage.ATOMIC_REPLAN,
                code,
                path,
                cause);
    }
}
