package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRepository;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationProtocolException;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationValidationException;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializer;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Composes one already-fenced active-Step completion persistence attempt. */
public final class DefaultActiveStepCompletionComposer
        implements ActiveStepCompletionComposer {
    private static final long ACTIVE_CHECKPOINT_VERSION = 3;
    private static final long ACTIVE_EVENT_SEQUENCE = 2;
    private static final long COMPLETED_CHECKPOINT_VERSION = 4;
    private static final long COMPLETION_EVENT_SEQUENCE = 3;

    private final ActiveStepCompletionMaterializer materializer;
    private final StepCompletionRepository repository;

    public DefaultActiveStepCompletionComposer(
            ActiveStepCompletionMaterializer materializer,
            StepCompletionRepository repository) {
        this.materializer = ActiveStepCompletionCompositionValues.required(
                materializer, "activeStepCompletionComposition.materializer");
        this.repository = ActiveStepCompletionCompositionValues.required(
                repository, "activeStepCompletionComposition.repository");
    }

    @Override
    public ActiveStepCompletionCompositionOutcome compose(
            ActiveStepCompletionMaterializationRequest request) {
        ActiveStepCompletionMaterializationRequest requiredRequest =
                ActiveStepCompletionCompositionValues.required(
                        request, "activeStepCompletionComposition.request");
        PlanId planId =
                requiredRequest.recoveredActiveStep().recovery().planId();
        StepCompletionRequest materialized =
                materialize(planId, requiredRequest);
        validateMaterialization(planId, requiredRequest, materialized);
        PersistenceResult<PersistedStepCompletion> result =
                persist(planId, materialized);
        return classify(requiredRequest, materialized, result);
    }

    private StepCompletionRequest materialize(
            PlanId planId,
            ActiveStepCompletionMaterializationRequest request) {
        try {
            StepCompletionRequest result = materializer.materialize(request);
            if (result == null) {
                throw protocol(
                        planId,
                        ActiveStepCompletionCompositionStage.MATERIALIZE,
                        ActiveStepCompletionCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "activeStepCompletionComposition.materialization",
                        null);
            }
            return result;
        } catch (ActiveStepCompletionMaterializationValidationException
                | ActiveStepCompletionMaterializationProtocolException
                        exception) {
            throw exception;
        } catch (ActiveStepCompletionCompositionProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    ActiveStepCompletionCompositionStage.MATERIALIZE,
                    ActiveStepCompletionCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    "activeStepCompletionComposition.materialization",
                    exception);
        }
    }

    private PersistenceResult<PersistedStepCompletion> persist(
            PlanId planId,
            StepCompletionRequest request) {
        try {
            PersistenceResult<PersistedStepCompletion> result =
                    repository.complete(request);
            if (result == null) {
                throw protocol(
                        planId,
                        ActiveStepCompletionCompositionStage.PERSIST,
                        ActiveStepCompletionCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "activeStepCompletionComposition.persistence",
                        null);
            }
            return result;
        } catch (ActiveStepCompletionCompositionProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    ActiveStepCompletionCompositionStage.PERSIST,
                    ActiveStepCompletionCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    "activeStepCompletionComposition.persistence",
                    exception);
        }
    }

    private static ActiveStepCompletionCompositionOutcome classify(
            ActiveStepCompletionMaterializationRequest input,
            StepCompletionRequest materialized,
            PersistenceResult<PersistedStepCompletion> result) {
        PlanId planId = materialized.planId();
        PersistenceOutcome outcome = result.outcome();
        if (outcome == null) {
            throw protocol(
                    planId,
                    ActiveStepCompletionCompositionStage.PERSIST,
                    ActiveStepCompletionCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "activeStepCompletionComposition.persistence.outcome",
                    null);
        }
        return switch (outcome) {
            case FOUND -> throw protocol(
                    planId,
                    ActiveStepCompletionCompositionStage.PERSIST,
                    ActiveStepCompletionCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "activeStepCompletionComposition.persistence.outcome",
                    null);
            case REJECTED -> {
                if (result.failure().isEmpty() || result.value().isPresent()) {
                    throw inconsistent(
                            planId,
                            "activeStepCompletionComposition.persistence"
                                    + ".failure");
                }
                yield new ActiveStepCompletionPersistenceRejected(
                        planId,
                        result.failure().orElseThrow(),
                        ActiveStepCompletionLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
            }
            case APPLIED, REPLAYED -> committed(input, materialized, result);
        };
    }

    private static ActiveStepCompletionCommitted committed(
            ActiveStepCompletionMaterializationRequest input,
            StepCompletionRequest materialized,
            PersistenceResult<PersistedStepCompletion> result) {
        PersistedStepCompletion persisted = result.value().orElse(null);
        if (persisted == null || result.failure().isPresent()) {
            throw inconsistent(
                    materialized.planId(),
                    "activeStepCompletionComposition.persistence.value");
        }
        LeaseRecord lease = input.recoveredActiveStep().lease();
        if (!persisted.planId().equals(materialized.planId())
                || !persisted.stepId().equals(materialized.stepId())
                || !persisted.leaseOwnerId().equals(lease.ownerId())
                || persisted.fencingToken() != lease.fencingToken()
                || !persisted.completionEvent().equals(
                        materialized.completionEvent())
                || !persisted.completedRevision().equals(
                        materialized.completedRevision())
                || persisted.completedCheckpoint().version()
                        != COMPLETED_CHECKPOINT_VERSION
                || !persisted.completedCheckpoint().checkpoint().equals(
                        materialized.completedCheckpoint())) {
            throw inconsistent(
                    materialized.planId(),
                    "activeStepCompletionComposition.persistence.value");
        }
        return new ActiveStepCompletionCommitted(
                result.outcome(),
                persisted,
                ActiveStepCompletionLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private static void validateMaterialization(
            PlanId planId,
            ActiveStepCompletionMaterializationRequest input,
            StepCompletionRequest output) {
        RecoveredActiveStep recovered = input.recoveredActiveStep();
        var active = recovered.recovery();
        var plan = active.plan();
        var current = active.checkpoint();
        var checkpoint = current.checkpoint();
        var currentRevision = plan.latestRevision();
        LeaseRecord lease = recovered.lease();
        PlanStepId stepId = active.activation().stepId();
        CompletionFact fact = output.completionFact();
        PlanRevision revision = output.completedRevision();
        Checkpoint completed = output.completedCheckpoint();

        Map<PlanStepId, CompletionFact> expectedFacts =
                new LinkedHashMap<>(currentRevision.completedFacts());
        expectedFacts.put(stepId, fact);
        List<ReceiptId> expectedReceipts =
                new ArrayList<>(checkpoint.receiptReferences());
        expectedReceipts.addAll(
                input.completionFactDraft().receiptReferences());
        Map<PlanStepId, StepExecutionState> expectedStates =
                new LinkedHashMap<>(checkpoint.stepStates());
        expectedStates.put(stepId, StepExecutionState.SUCCEEDED);
        boolean allSucceeded = expectedStates.values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED);

        boolean exact =
                recovered.leaseDisposition()
                                == StepRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY
                        && output.planId().equals(planId)
                        && output.leaseToken().equals(lease.leaseToken())
                        && output.fencingToken() == lease.fencingToken()
                        && output.expectedRevisionId()
                                .equals(currentRevision.id())
                        && output.expectedRevisionNumber()
                                == currentRevision.number()
                        && output.expectedCheckpointVersion()
                                == ACTIVE_CHECKPOINT_VERSION
                        && output.expectedEventHeadSequence()
                                == ACTIVE_EVENT_SEQUENCE
                        && output.stepId().equals(stepId)
                        && fact.stepId().equals(stepId)
                        && fact.outcomeHash().equals(
                                input.completionFactDraft().outcomeHash())
                        && fact.completedAt().equals(
                                input.completionFactDraft().completedAt())
                        && fact.receiptReferences().equals(
                                input.completionFactDraft().receiptReferences())
                        && expectedEvent(
                                input, active.taskFrame().id(), planId,
                                output.completionEvent())
                        && revision.id().equals(input.revisionDraft().id())
                        && revision.taskFrameId().equals(active.taskFrame().id())
                        && revision.number() == currentRevision.number() + 1
                        && revision.parentRevisionId().equals(
                                java.util.Optional.of(currentRevision.id()))
                        && revision.reason().equals(input.revisionDraft().reason())
                        && revision.createdAt().equals(
                                input.revisionDraft().createdAt())
                        && revision.steps().equals(currentRevision.steps())
                        && revision.completedFacts().equals(expectedFacts)
                        && completed.taskFrameId().equals(active.taskFrame().id())
                        && completed.planId().equals(planId)
                        && completed.revisionId().equals(revision.id())
                        && completed.revisionNumber() == revision.number()
                        && completed.lastEventSequence()
                                == COMPLETION_EVENT_SEQUENCE
                        && completed.planState() == (allSucceeded
                                ? PlanExecutionState.SUCCEEDED
                                : PlanExecutionState.ACTIVE)
                        && completed.stepStates().equals(expectedStates)
                        && completed.receiptReferences().equals(expectedReceipts)
                        && completed.createdAt().equals(
                                input.checkpointCreatedAt());
        if (!exact) {
            throw protocol(
                    planId,
                    ActiveStepCompletionCompositionStage.MATERIALIZE,
                    ActiveStepCompletionCompositionProtocolCode
                            .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                    "activeStepCompletionComposition.materialization.value",
                    null);
        }
    }

    private static boolean expectedEvent(
            ActiveStepCompletionMaterializationRequest input,
            io.paperagent.v2.contracts.TaskFrameId taskFrameId,
            PlanId planId,
            EventEnvelope event) {
        var draft = input.eventDraft();
        return event.id().equals(draft.id())
                && event.taskFrameId().equals(taskFrameId)
                && event.planId().equals(planId)
                && event.sequence() == COMPLETION_EVENT_SEQUENCE
                && event.occurredAt().equals(draft.occurredAt())
                && event.type().equals(draft.type())
                && event.causationId().equals(draft.causationId())
                && event.correlationId().equals(draft.correlationId())
                && event.payload().equals(draft.payload());
    }

    private static ActiveStepCompletionCompositionProtocolException
            inconsistent(PlanId planId, String path) {
        return protocol(
                planId,
                ActiveStepCompletionCompositionStage.PERSIST,
                ActiveStepCompletionCompositionProtocolCode
                        .INCONSISTENT_PERSISTENCE_RESULT,
                path,
                null);
    }

    private static ActiveStepCompletionCompositionProtocolException protocol(
            PlanId planId,
            ActiveStepCompletionCompositionStage stage,
            ActiveStepCompletionCompositionProtocolCode code,
            String path,
            Throwable cause) {
        return new ActiveStepCompletionCompositionProtocolException(
                planId, stage, code, path, cause);
    }
}
