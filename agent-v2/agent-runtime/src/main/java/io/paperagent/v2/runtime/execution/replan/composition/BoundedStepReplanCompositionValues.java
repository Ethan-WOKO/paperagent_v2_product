package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopTurnLimitReached;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

import java.util.Set;

final class BoundedStepReplanCompositionValues {
    private static final Set<String> VALIDATION_PATHS = Set.of(
            "boundedStepReplanComposition.activeStepReplanRepository",
            "boundedStepReplanComposition.recoveredActiveStep",
            "boundedStepReplanComposition.turnLimitReached",
            "boundedStepReplanComposition.activeStepReplanRequest",
            "boundedStepReplanApplied.persistedReplan",
            "boundedStepReplanReplayed.persistedReplan",
            "boundedStepReplanPersistenceRejected.planId",
            "boundedStepReplanPersistenceRejected.failure");

    private static final Set<String> PROTOCOL_BASE_PATHS = Set.of(
            "boundedStepReplanComposition.authority",
            "boundedStepReplanComposition.replanResult");

    private BoundedStepReplanCompositionValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(
                    BoundedStepReplanCompositionValidationCode.REQUIRED_VALUE_MISSING,
                    path);
        }
        return value;
    }

    static void requireAuthority(
            RecoveredActiveStep recoveredActiveStep,
            BoundedStepAgentLoopTurnLimitReached turnLimitReached,
            ActiveStepReplanRequest activeStepReplanRequest) {
        PersistedStepRecoveryActive recovery = recoveredActiveStep.recovery();
        LeaseRecord lease = recoveredActiveStep.lease();
        PersistedStepActivation activation = recovery.activation();
        Checkpoint checkpoint = recovery.checkpoint().checkpoint();
        PlanId planId = recoveredActiveStep.planId();
        PlanStepId stepId = activation.stepId();

        boolean recoveredAuthorityMatches = recovery.plan().id().equals(planId)
                && recovery.plan().taskFrameId().equals(recovery.taskFrame().id())
                && checkpoint.planId().equals(planId)
                && checkpoint.taskFrameId().equals(recovery.taskFrame().id())
                && checkpoint.revisionId().equals(recovery.plan().latestRevision().id())
                && checkpoint.revisionNumber()
                        == recovery.plan().latestRevision().number()
                && activation.planId().equals(planId)
                && activation.activatedCheckpoint().equals(recovery.checkpoint())
                && activation.leaseOwnerId().equals(lease.ownerId())
                && activation.fencingToken() == lease.fencingToken();
        boolean requestMatches = turnLimitReached.planId().equals(planId)
                && turnLimitReached.stepId().equals(stepId)
                && activeStepReplanRequest.planId().equals(planId)
                && activeStepReplanRequest.activeStepId().equals(stepId)
                && activeStepReplanRequest.leaseToken().equals(lease.leaseToken())
                && activeStepReplanRequest.fencingToken() == lease.fencingToken()
                && activeStepReplanRequest.expectedRevisionId()
                        .equals(checkpoint.revisionId())
                && activeStepReplanRequest.expectedRevisionNumber()
                        == checkpoint.revisionNumber()
                && activeStepReplanRequest.expectedCheckpointVersion()
                        == recovery.checkpoint().version()
                && activeStepReplanRequest.expectedEventHeadSequence()
                        == checkpoint.lastEventSequence();
        boolean intentsMatch = turnLimitReached.persistedIntents().stream()
                .allMatch(intent -> intentMatches(
                        intent, planId, stepId, lease, activation));
        if (!recoveredAuthorityMatches || !requestMatches || !intentsMatch) {
            throw protocolFailure(
                    planId,
                    stepId,
                    BoundedStepReplanCompositionStage.AUTHORITY_VALIDATION,
                    BoundedStepReplanCompositionProtocolCode
                            .INCONSISTENT_REQUEST_AUTHORITY,
                    "boundedStepReplanComposition.authority",
                    null);
        }
    }

    static void requireSuccess(PersistedActiveStepReplan persisted, String path) {
        required(persisted, path);
    }

    static void requireRejected(PlanId planId, PersistenceFailure failure) {
        required(planId, "boundedStepReplanPersistenceRejected.planId");
        required(failure, "boundedStepReplanPersistenceRejected.failure");
    }

    static boolean matchesRequest(
            PersistedActiveStepReplan persisted,
            ActiveStepReplanRequest request,
            String leaseOwnerId) {
        return persisted.planId().equals(request.planId())
                && persisted.supersededStepId().equals(request.activeStepId())
                && persisted.leaseOwnerId().equals(leaseOwnerId)
                && persisted.fencingToken() == request.fencingToken()
                && persisted.supersessionEvent().equals(request.supersessionEvent())
                && persisted.supersededCheckpoint().version()
                        == request.expectedCheckpointVersion() + 1
                && persisted.supersededCheckpoint().checkpoint()
                        .equals(request.supersededCheckpoint())
                && persisted.replanEvent().equals(request.replanEvent())
                && persisted.replannedRevision().equals(request.replannedRevision())
                && persisted.replannedCheckpoint().version()
                        == request.expectedCheckpointVersion() + 2
                && persisted.replannedCheckpoint().checkpoint()
                        .equals(request.replannedCheckpoint());
    }

    static BoundedStepReplanCompositionValidationException failure(
            BoundedStepReplanCompositionValidationCode code,
            String path) {
        return new BoundedStepReplanCompositionValidationException(
                requiredInternal(code, "code"), requiredInternal(path, "path"));
    }

    static BoundedStepReplanCompositionProtocolException protocolFailure(
            PlanId planId,
            PlanStepId stepId,
            BoundedStepReplanCompositionStage stage,
            BoundedStepReplanCompositionProtocolCode code,
            String path,
            Throwable cause) {
        return new BoundedStepReplanCompositionProtocolException(
                requiredInternal(planId, "planId"),
                requiredInternal(stepId, "stepId"),
                requiredInternal(stage, "stage"),
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                cause);
    }

    static String validationPath(String path) {
        requiredInternal(path, "path");
        if (!VALIDATION_PATHS.contains(path)) {
            throw new IllegalArgumentException("path is not in the validation lexicon");
        }
        return path;
    }

    static String protocolPath(String path) {
        requiredInternal(path, "path");
        for (String base : PROTOCOL_BASE_PATHS) {
            if (path.equals(base)
                    || path.equals(base + ".outcome")
                    || path.equals(base + ".value")
                    || path.equals(base + ".failure")) {
                return path;
            }
        }
        throw new IllegalArgumentException("path is not in the protocol lexicon");
    }

    static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static boolean intentMatches(
            PersistedEffectIntent persistedIntent,
            PlanId planId,
            PlanStepId stepId,
            LeaseRecord lease,
            PersistedStepActivation activation) {
        return persistedIntent.intent().planId().equals(planId)
                && persistedIntent.intent().stepId().equals(stepId)
                && persistedIntent.leaseOwnerId().equals(lease.ownerId())
                && persistedIntent.fencingToken() == lease.fencingToken()
                && persistedIntent.activationEventId()
                        .equals(activation.activationEvent().id());
    }
}
