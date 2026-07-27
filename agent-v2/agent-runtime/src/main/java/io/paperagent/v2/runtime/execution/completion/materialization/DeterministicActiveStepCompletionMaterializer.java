package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class DeterministicActiveStepCompletionMaterializer
        implements ActiveStepCompletionMaterializer {
    private static final long ACTIVE_CHECKPOINT_VERSION = 3;
    private static final long ACTIVE_EVENT_SEQUENCE = 2;
    private static final long COMPLETION_EVENT_SEQUENCE = 3;

    public DeterministicActiveStepCompletionMaterializer() {
    }

    @Override
    public StepCompletionRequest materialize(
            ActiveStepCompletionMaterializationRequest request) {
        ActiveStepCompletionMaterializationRequest requiredRequest =
                ActiveStepCompletionMaterializationValues.required(
                        request,
                        "activeStepCompletionMaterializationRequest");
        Authority authority = requireAuthority(
                requiredRequest.recoveredActiveStep());
        validateDrafts(requiredRequest, authority);

        CompletionFact fact;
        PlanRevision completedRevision;
        EventEnvelope event;
        Plan completedPlan;
        Checkpoint completedCheckpoint;
        try {
            fact = completionFact(requiredRequest, authority);
            completedRevision = completedRevision(
                    requiredRequest, authority, fact);
            event = completionEvent(requiredRequest, authority);
            completedPlan = completedPlan(authority, completedRevision);
            completedCheckpoint = completedCheckpoint(
                    requiredRequest, authority, completedRevision);
        } catch (IllegalArgumentException invalid) {
            if (invalid
                    instanceof ActiveStepCompletionMaterializationValidationException) {
                throw invalid;
            }
            throw ActiveStepCompletionMaterializationValues.protocol(
                    ActiveStepCompletionMaterializationProtocolCode
                            .CONTRACT_VALIDATION_FAILED,
                    ActiveStepCompletionMaterializationStage.INPUT,
                    "activeStepCompletionMaterializationRequest");
        }

        if (!CheckpointValidators.validate(
                        completedCheckpoint,
                        authority.taskFrame(),
                        completedPlan,
                        authority.current())
                .isEmpty()) {
            throw ActiveStepCompletionMaterializationValues.protocol(
                    ActiveStepCompletionMaterializationProtocolCode
                            .CHECKPOINT_VALIDATION_FAILED,
                    ActiveStepCompletionMaterializationStage.CHECKPOINT,
                    "completedCheckpoint");
        }

        PlanRevision currentRevision = authority.plan().latestRevision();
        LeaseRecord lease = authority.lease();
        return new StepCompletionRequest(
                authority.plan().id(),
                lease.leaseToken(),
                lease.fencingToken(),
                currentRevision.id(),
                currentRevision.number(),
                ACTIVE_CHECKPOINT_VERSION,
                ACTIVE_EVENT_SEQUENCE,
                authority.stepId(),
                fact,
                event,
                completedRevision,
                completedCheckpoint);
    }

    private static void validateDrafts(
            ActiveStepCompletionMaterializationRequest request,
            Authority authority) {
        Instant activeAt = authority.current().createdAt();
        requireNotBefore(
                request.completionFactDraft().completedAt(),
                activeAt,
                ActiveStepCompletionMaterializationStage.COMPLETION_FACT,
                "completionFactDraft.completedAt");
        requireNotBefore(
                request.eventDraft().occurredAt(),
                activeAt,
                ActiveStepCompletionMaterializationStage.EVENT,
                "completionEventDraft.occurredAt");
        requireNotBefore(
                request.revisionDraft().createdAt(),
                activeAt,
                ActiveStepCompletionMaterializationStage.REVISION,
                "completionRevisionDraft.createdAt");
        requireNotBefore(
                request.checkpointCreatedAt(),
                activeAt,
                ActiveStepCompletionMaterializationStage.CHECKPOINT,
                "activeStepCompletionMaterializationRequest"
                        + ".checkpointCreatedAt");

        Set<ReceiptId> existing = Set.copyOf(
                authority.current().receiptReferences());
        if (request.completionFactDraft().receiptReferences().stream()
                .anyMatch(existing::contains)) {
            throw ActiveStepCompletionMaterializationValues.validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .RECEIPT_OVERLAP,
                    ActiveStepCompletionMaterializationStage.COMPLETION_FACT,
                    "completionFactDraft.receiptReferences");
        }
        if (authority.plan().revisions().stream()
                .anyMatch(revision -> revision.id()
                        .equals(request.revisionDraft().id()))) {
            throw ActiveStepCompletionMaterializationValues.validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .REVISION_ID_REUSE,
                    ActiveStepCompletionMaterializationStage.REVISION,
                    "completionRevisionDraft.id");
        }
        if (authority.plan().latestRevision().number() == Long.MAX_VALUE) {
            throw ActiveStepCompletionMaterializationValues.validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .REVISION_NUMBER_OVERFLOW,
                    ActiveStepCompletionMaterializationStage.REVISION,
                    "recoveredActiveStep.recovery.plan.latestRevision.number");
        }
        if (request.eventDraft().id()
                .equals(authority.activation().activationEvent().id())) {
            throw ActiveStepCompletionMaterializationValues.validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .EVENT_ID_CONFLICT,
                    ActiveStepCompletionMaterializationStage.EVENT,
                    "completionEventDraft.id");
        }
    }

    private static void requireNotBefore(
            Instant proposedInstant,
            Instant authority,
            ActiveStepCompletionMaterializationStage stage,
            String path) {
        if (proposedInstant.isBefore(authority)) {
            throw ActiveStepCompletionMaterializationValues.validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .TIME_REGRESSION,
                    stage,
                    path);
        }
    }

    private static CompletionFact completionFact(
            ActiveStepCompletionMaterializationRequest request,
            Authority authority) {
        ActiveStepCompletionFactDraft draft = request.completionFactDraft();
        return new CompletionFact(
                authority.stepId(),
                draft.outcomeHash(),
                draft.completedAt(),
                draft.receiptReferences());
    }

    private static PlanRevision completedRevision(
            ActiveStepCompletionMaterializationRequest request,
            Authority authority,
            CompletionFact fact) {
        PlanRevision current = authority.plan().latestRevision();
        Map<PlanStepId, CompletionFact> facts =
                new LinkedHashMap<>(current.completedFacts());
        facts.put(authority.stepId(), fact);
        ActiveStepCompletionRevisionDraft draft = request.revisionDraft();
        return new PlanRevision(
                draft.id(),
                current.taskFrameId(),
                current.number() + 1,
                java.util.Optional.of(current.id()),
                draft.reason(),
                draft.createdAt(),
                current.steps(),
                facts);
    }

    private static EventEnvelope completionEvent(
            ActiveStepCompletionMaterializationRequest request,
            Authority authority) {
        ActiveStepCompletionEventDraft draft = request.eventDraft();
        return new EventEnvelope(
                draft.id(),
                authority.taskFrame().id(),
                authority.plan().id(),
                COMPLETION_EVENT_SEQUENCE,
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                draft.payload());
    }

    private static Plan completedPlan(
            Authority authority,
            PlanRevision completedRevision) {
        List<PlanRevision> revisions =
                new ArrayList<>(authority.plan().revisions());
        revisions.add(completedRevision);
        return new Plan(
                authority.plan().id(),
                authority.plan().taskFrameId(),
                revisions);
    }

    private static Checkpoint completedCheckpoint(
            ActiveStepCompletionMaterializationRequest request,
            Authority authority,
            PlanRevision completedRevision) {
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(authority.current().stepStates());
        states.put(authority.stepId(), StepExecutionState.SUCCEEDED);
        List<ReceiptId> receipts =
                new ArrayList<>(authority.current().receiptReferences());
        receipts.addAll(
                request.completionFactDraft().receiptReferences());
        boolean allSucceeded = states.values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED);
        return new Checkpoint(
                authority.current().taskFrameId(),
                authority.current().planId(),
                completedRevision.id(),
                completedRevision.number(),
                COMPLETION_EVENT_SEQUENCE,
                allSucceeded
                        ? PlanExecutionState.SUCCEEDED
                        : PlanExecutionState.ACTIVE,
                states,
                receipts,
                request.checkpointCreatedAt());
    }

    private static Authority requireAuthority(RecoveredActiveStep recovered) {
        PersistedStepRecoveryActive active = recovered.recovery();
        TaskFrame taskFrame = active.taskFrame();
        Plan plan = active.plan();
        VersionedCheckpoint versioned = active.checkpoint();
        Checkpoint checkpoint = versioned.checkpoint();
        PersistedStepActivation activation = active.activation();
        LeaseRecord lease = recovered.lease();
        PlanRevision revision = plan.latestRevision();
        PlanStepId stepId = activation.stepId();

        Set<PlanStepId> revisionStepIds = revision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        boolean baseConsistent =
                recovered.leaseDisposition()
                                == StepRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY
                        && plan.id().equals(active.planId())
                        && plan.taskFrameId().equals(taskFrame.id())
                        && versioned.version() == ACTIVE_CHECKPOINT_VERSION
                        && checkpoint.taskFrameId().equals(taskFrame.id())
                        && checkpoint.planId().equals(plan.id())
                        && checkpoint.revisionId().equals(revision.id())
                        && checkpoint.revisionNumber() == revision.number()
                        && checkpoint.lastEventSequence()
                                == ACTIVE_EVENT_SEQUENCE
                        && checkpoint.planState() == PlanExecutionState.ACTIVE
                        && lease.planId().equals(plan.id())
                        && activation.planId().equals(plan.id())
                        && activation.activatedCheckpoint().version()
                                == ACTIVE_CHECKPOINT_VERSION
                        && activation.activatedCheckpoint().equals(versioned)
                        && activation.activationEvent().planId()
                                .equals(plan.id())
                        && activation.activationEvent().taskFrameId()
                                .equals(taskFrame.id())
                        && activation.activationEvent().sequence()
                                == ACTIVE_EVENT_SEQUENCE
                        && revisionStepIds.contains(stepId)
                        && checkpoint.stepStates().keySet()
                                .equals(revisionStepIds)
                        && CheckpointValidators.validate(
                                        checkpoint, taskFrame, plan, null)
                                .isEmpty();
        if (!baseConsistent) {
            throw authorityFailure();
        }

        if (revision.completedFacts().containsKey(stepId)
                || checkpoint.stepStates().get(stepId)
                        != StepExecutionState.ACTIVE) {
            throw stepFailure();
        }
        int activeCount = 0;
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : checkpoint.stepStates().entrySet()) {
            StepExecutionState state = entry.getValue();
            if (state == StepExecutionState.ACTIVE) {
                activeCount++;
            }
            if (!entry.getKey().equals(stepId)
                    && state != StepExecutionState.NOT_STARTED
                    && state != StepExecutionState.SUCCEEDED) {
                throw stepFailure();
            }
        }
        if (activeCount != 1) {
            throw stepFailure();
        }
        return new Authority(
                taskFrame,
                plan,
                checkpoint,
                activation,
                stepId,
                lease);
    }

    private static ActiveStepCompletionMaterializationProtocolException
            authorityFailure() {
        return ActiveStepCompletionMaterializationValues.protocol(
                ActiveStepCompletionMaterializationProtocolCode
                        .INCONSISTENT_RECOVERED_AUTHORITY,
                ActiveStepCompletionMaterializationStage.RECOVERED_AUTHORITY,
                "recoveredActiveStep");
    }

    private static ActiveStepCompletionMaterializationProtocolException
            stepFailure() {
        return ActiveStepCompletionMaterializationValues.protocol(
                ActiveStepCompletionMaterializationProtocolCode
                        .STEP_NOT_ELIGIBLE,
                ActiveStepCompletionMaterializationStage.RECOVERED_AUTHORITY,
                "recoveredActiveStep.recovery.activation.stepId");
    }

    private record Authority(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint current,
            PersistedStepActivation activation,
            PlanStepId stepId,
            LeaseRecord lease) {
    }
}
