package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;

/** Composes one fenced, observation-only active-Step recovery handoff. */
public final class DefaultStepRecoverer implements StepRecoverer {
    private final StepRecoveryRepository stepRecoveryRepository;
    private final LeaseRepository leaseRepository;

    public DefaultStepRecoverer(
            StepRecoveryRepository stepRecoveryRepository,
            LeaseRepository leaseRepository) {
        this.stepRecoveryRepository = StepRecoveryCompositionValues.required(
                stepRecoveryRepository, "stepRecoveryComposition.stepRecoveryRepository");
        this.leaseRepository = StepRecoveryCompositionValues.required(
                leaseRepository, "stepRecoveryComposition.leaseRepository");
    }

    @Override
    public StepRecoveryCompositionOutcome recover(StepRecoveryRequest request) {
        StepRecoveryRequest requiredRequest = StepRecoveryCompositionValues.required(
                request, "stepRecoveryComposition.request");
        PlanId planId = requiredRequest.planId();

        Inspection initial = inspect(
                planId,
                StepRecoveryStage.INITIAL_INSPECT,
                "stepRecoveryComposition.initialInspectResult",
                StepRecoveryLeaseDisposition.NO_LEASE_ACTION);
        if (initial.rejected() != null) {
            return new StepRecoveryPersistenceRejected(
                    planId,
                    StepRecoveryStage.INITIAL_INSPECT,
                    initial.rejected(),
                    StepRecoveryLeaseDisposition.NO_LEASE_ACTION);
        }

        LeaseRecord lease;
        try {
            lease = acquire(planId, requiredRequest.leaseAttempt());
        } catch (LeaseRejectedSignal rejected) {
            return new StepRecoveryLeaseRejected(
                    planId,
                    rejected.failure(),
                    StepRecoveryLeaseDisposition.NOT_ACQUIRED);
        }

        Inspection postLease = inspect(
                planId,
                StepRecoveryStage.POST_LEASE_INSPECT,
                "stepRecoveryComposition.postLeaseInspectResult",
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        if (postLease.rejected() != null) {
            return new StepRecoveryPersistenceRejected(
                    planId,
                    StepRecoveryStage.POST_LEASE_INSPECT,
                    postLease.rejected(),
                    StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        }

        return new RecoveredActiveStep(
                postLease.active(),
                lease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private Inspection inspect(
            PlanId planId,
            StepRecoveryStage stage,
            String resultPath,
            StepRecoveryLeaseDisposition leaseDisposition) {
        PersistenceResult<StepRecoverySnapshot> result;
        try {
            result = stepRecoveryRepository.inspect(planId);
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    stage,
                    StepRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                    resultPath,
                    leaseDisposition,
                    exception);
        }
        if (result == null) {
            throw protocol(
                    planId,
                    stage,
                    StepRecoveryProtocolCode.NULL_COLLABORATOR_RESULT,
                    resultPath,
                    leaseDisposition,
                    null);
        }
        if (result.outcome() == null) {
            throw protocol(
                    planId,
                    stage,
                    StepRecoveryProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    resultPath + ".outcome",
                    leaseDisposition,
                    null);
        }
        return switch (result.outcome()) {
            case FOUND -> foundInspection(planId, stage, resultPath, leaseDisposition, result);
            case REJECTED -> rejectedInspection(
                    planId, stage, resultPath, leaseDisposition, result);
            case APPLIED, REPLAYED -> throw protocol(
                    planId,
                    stage,
                    StepRecoveryProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    resultPath + ".outcome",
                    leaseDisposition,
                    null);
        };
    }

    private static Inspection foundInspection(
            PlanId planId,
            StepRecoveryStage stage,
            String resultPath,
            StepRecoveryLeaseDisposition leaseDisposition,
            PersistenceResult<StepRecoverySnapshot> result) {
        Object value = result.value().orElse(null);
        if (!(value instanceof PersistedStepRecoveryActive active)
                || !active.planId().equals(planId)) {
            throw protocol(
                    planId,
                    stage,
                    StepRecoveryProtocolCode.INCONSISTENT_INSPECTION_RESULT,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        return new Inspection(active, null);
    }

    private static Inspection rejectedInspection(
            PlanId planId,
            StepRecoveryStage stage,
            String resultPath,
            StepRecoveryLeaseDisposition leaseDisposition,
            PersistenceResult<StepRecoverySnapshot> result) {
        Object failureValue = result.failure().orElse(null);
        if (!(failureValue instanceof PersistenceFailure failure)
                || !StepRecoveryCompositionValues.isTypedInspectionFailure(failure)) {
            throw protocol(
                    planId,
                    stage,
                    StepRecoveryProtocolCode.INCONSISTENT_INSPECTION_RESULT,
                    resultPath + ".failure",
                    leaseDisposition,
                    null);
        }
        return new Inspection(null, failure);
    }

    private LeaseRecord acquire(
            PlanId planId,
            StepRecoveryLeaseAttempt attempt) {
        PersistenceResult<LeaseRecord> result;
        try {
            result = leaseRepository.acquire(
                    planId,
                    attempt.leaseOwnerId(),
                    attempt.leaseToken(),
                    attempt.leaseExpiresAt());
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    StepRecoveryStage.LEASE_ACQUIRE,
                    StepRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                    "stepRecoveryComposition.leaseAcquireResult",
                    StepRecoveryLeaseDisposition.ACQUISITION_INDETERMINATE,
                    exception);
        }
        if (result == null) {
            throw protocol(
                    planId,
                    StepRecoveryStage.LEASE_ACQUIRE,
                    StepRecoveryProtocolCode.NULL_COLLABORATOR_RESULT,
                    "stepRecoveryComposition.leaseAcquireResult",
                    StepRecoveryLeaseDisposition.ACQUISITION_INDETERMINATE,
                    null);
        }
        if (result.outcome() == null) {
            throw protocol(
                    planId,
                    StepRecoveryStage.LEASE_ACQUIRE,
                    StepRecoveryProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "stepRecoveryComposition.leaseAcquireResult.outcome",
                    StepRecoveryLeaseDisposition.ACQUISITION_INDETERMINATE,
                    null);
        }
        return switch (result.outcome()) {
            case REJECTED -> leaseRejected(planId, result);
            case APPLIED, REPLAYED -> validateLease(planId, attempt, result);
            case FOUND -> throw protocol(
                    planId,
                    StepRecoveryStage.LEASE_ACQUIRE,
                    StepRecoveryProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "stepRecoveryComposition.leaseAcquireResult.outcome",
                    StepRecoveryLeaseDisposition.ACQUISITION_INDETERMINATE,
                    null);
        };
    }

    private static LeaseRecord leaseRejected(
            PlanId planId,
            PersistenceResult<LeaseRecord> result) {
        PersistenceFailure failure = result.failure().orElse(null);
        if (failure == null) {
            throw protocol(
                    planId,
                    StepRecoveryStage.LEASE_ACQUIRE,
                    StepRecoveryProtocolCode.INCONSISTENT_LEASE_AUTHORITY,
                    "stepRecoveryComposition.leaseAcquireResult.failure",
                    StepRecoveryLeaseDisposition.NOT_ACQUIRED,
                    null);
        }
        throw new LeaseRejectedSignal(failure);
    }

    private static LeaseRecord validateLease(
            PlanId planId,
            StepRecoveryLeaseAttempt attempt,
            PersistenceResult<LeaseRecord> result) {
        LeaseRecord lease = result.value().orElse(null);
        if (lease == null
                || !lease.planId().equals(planId)
                || !lease.ownerId().equals(attempt.leaseOwnerId())
                || !lease.leaseToken().equals(attempt.leaseToken())
                || !lease.expiresAt().equals(attempt.leaseExpiresAt())) {
            throw protocol(
                    planId,
                    StepRecoveryStage.LEASE_ACQUIRE,
                    StepRecoveryProtocolCode.INCONSISTENT_LEASE_AUTHORITY,
                    "stepRecoveryComposition.leaseAcquireResult.value",
                    StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        return lease;
    }

    private static StepRecoveryProtocolException protocol(
            PlanId planId,
            StepRecoveryStage stage,
            StepRecoveryProtocolCode code,
            String path,
            StepRecoveryLeaseDisposition leaseDisposition,
            Throwable cause) {
        return StepRecoveryCompositionValues.protocolFailure(
                planId, stage, code, path, leaseDisposition, cause);
    }

    private record Inspection(
            PersistedStepRecoveryActive active,
            PersistenceFailure rejected) {
    }

    private static final class LeaseRejectedSignal extends RuntimeException {
        private final PersistenceFailure failure;

        private LeaseRejectedSignal(PersistenceFailure failure) {
            this.failure = failure;
        }

        private PersistenceFailure failure() {
            return failure;
        }
    }
}
