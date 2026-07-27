package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionOutcome;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionRequest;
import org.springframework.stereotype.Service;

import java.util.HashSet;

/**
 * Internal first-Step activation composition for an authenticated Agent turn.
 */
@Service
public class AuthenticatedAgentTurnStepActivationComposer {
    private static final String ROOT = "authenticatedStepActivation";

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final ExecutionStartRecoveryRepository executionStarts;
    private final StepActivationComposer composer;

    public AuthenticatedAgentTurnStepActivationComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            ExecutionStartRecoveryRepository executionStarts,
            StepActivationComposer composer) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.executionStarts = executionStarts;
        this.composer = composer;
    }

    public StepActivationCompositionOutcome activate(
            Long userId,
            Long turnId,
            AuthenticatedAgentTurnStepActivationCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PlanId planId = planIds.derive(context.identity());
        requireCommand(command);
        PersistedExecutionStartCommitted committed = inspectCommitted(planId);
        return composer.compose(new StepActivationCompositionRequest(
                committed, command.stepId(), command.attempt()));
    }

    private static void requireCommand(
            AuthenticatedAgentTurnStepActivationCommand command) {
        if (command == null) {
            throw failure(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .REQUIRED_VALUE_MISSING,
                    ROOT + ".command");
        }
        if (command.stepId() == null) {
            throw failure(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .REQUIRED_VALUE_MISSING,
                    ROOT + ".command.stepId");
        }
        if (command.attempt() == null) {
            throw failure(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .REQUIRED_VALUE_MISSING,
                    ROOT + ".command.attempt");
        }
    }

    private PersistedExecutionStartCommitted inspectCommitted(PlanId planId) {
        PersistenceResult<ExecutionStartRecoverySnapshot> inspection;
        try {
            inspection = executionStarts.inspect(planId);
        } catch (RuntimeException exception) {
            throw new AuthenticatedAgentTurnStepActivationCompositionException(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .INSPECTION_COLLABORATOR_FAILURE,
                    ROOT + ".executionStartInspection",
                    exception);
        }
        if (inspection == null) {
            throw failure(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .INVALID_INSPECTION_RESULT,
                    ROOT + ".executionStartInspection");
        }

        final PersistenceOutcome outcome;
        final ExecutionStartRecoverySnapshot snapshot;
        final PersistenceFailure persistenceFailure;
        try {
            outcome = inspection.outcome();
            snapshot = inspection.value().orElse(null);
            persistenceFailure = inspection.failure().orElse(null);
        } catch (RuntimeException exception) {
            throw new AuthenticatedAgentTurnStepActivationCompositionException(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .INVALID_INSPECTION_RESULT,
                    ROOT + ".executionStartInspection",
                    exception);
        }

        if (outcome == PersistenceOutcome.REJECTED) {
            if (persistenceFailure == null || snapshot != null) {
                throw failure(
                        AuthenticatedAgentTurnStepActivationCompositionCode
                                .INVALID_INSPECTION_RESULT,
                        ROOT + ".executionStartInspection");
            }
            throw failure(
                    persistenceFailure.code() == PersistenceErrorCode.NOT_FOUND
                            ? AuthenticatedAgentTurnStepActivationCompositionCode
                                    .EXECUTION_START_NOT_FOUND
                            : AuthenticatedAgentTurnStepActivationCompositionCode
                                    .EXECUTION_START_REJECTED,
                    ROOT + ".executionStartInspection");
        }
        if (outcome != PersistenceOutcome.FOUND) {
            throw failure(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .INVALID_INSPECTION_RESULT,
                    ROOT + ".executionStartInspection");
        }
        if (persistenceFailure != null || snapshot == null) {
            throw failure(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .INVALID_INSPECTION_RESULT,
                    ROOT + ".executionStartInspection");
        }
        if (!(snapshot instanceof PersistedExecutionStartCommitted committed)) {
            throw failure(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .EXECUTION_START_NOT_COMMITTED,
                    ROOT + ".executionStartInspection.value");
        }
        validateCommitted(planId, committed);
        return committed;
    }

    private static void validateCommitted(
            PlanId planId,
            PersistedExecutionStartCommitted committed) {
        try {
            EventEnvelope startEvent = committed.executionStart().startEvent();
            Checkpoint initialCheckpoint =
                    committed.bootstrap().initialCheckpoint().checkpoint();
            Checkpoint startedCheckpoint =
                    committed.executionStart().startedCheckpoint().checkpoint();
            var taskFrameId = committed.bootstrap().taskFrame().id();
            var bootstrapPlan = committed.bootstrap().plan();
            var currentPlan = committed.currentPlan();
            PlanRevision bootstrapRevision = bootstrapPlan.latestRevision();
            PlanRevision currentRevision = currentPlan.latestRevision();
            var bootstrapStepIds = new HashSet<>(
                    bootstrapRevision.steps().stream()
                            .map(step -> step.id())
                            .toList());
            var currentStepIds = new HashSet<>(
                    currentRevision.steps().stream()
                            .map(step -> step.id())
                            .toList());
            boolean identityBound = planId.equals(committed.planId())
                    && planId.equals(bootstrapPlan.id())
                    && planId.equals(currentPlan.id())
                    && planId.equals(committed.executionStart().planId())
                    && taskFrameId.equals(bootstrapPlan.taskFrameId())
                    && taskFrameId.equals(currentPlan.taskFrameId());
            if (!identityBound) {
                throw failure(
                        AuthenticatedAgentTurnStepActivationCompositionCode
                                .EXECUTION_START_PLAN_MISMATCH,
                        ROOT + ".executionStartInspection.value");
            }
            boolean structurallyCommitted =
                    currentPlan.revisions().size()
                            >= bootstrapPlan.revisions().size()
                    && currentPlan.revisions()
                            .subList(0, bootstrapPlan.revisions().size())
                            .equals(bootstrapPlan.revisions())
                    && committed.bootstrap().initialCheckpoint().version() == 1
                    && planId.equals(initialCheckpoint.planId())
                    && taskFrameId.equals(initialCheckpoint.taskFrameId())
                    && initialCheckpoint.revisionId()
                            .equals(bootstrapRevision.id())
                    && initialCheckpoint.revisionNumber()
                            == bootstrapRevision.number()
                    && initialCheckpoint.lastEventSequence() == 0
                    && initialCheckpoint.planState()
                            == PlanExecutionState.NOT_STARTED
                    && initialCheckpoint.stepStates().keySet()
                            .equals(bootstrapStepIds)
                    && initialCheckpoint.stepStates().values().stream()
                            .allMatch(state ->
                                    state == StepExecutionState.NOT_STARTED)
                    && initialCheckpoint.receiptReferences().isEmpty()
                    && committed.executionStart().startedCheckpoint().version() == 2
                    && planId.equals(startEvent.planId())
                    && taskFrameId.equals(startEvent.taskFrameId())
                    && startEvent.sequence() == 1
                    && planId.equals(startedCheckpoint.planId())
                    && taskFrameId.equals(startedCheckpoint.taskFrameId())
                    && startedCheckpoint.revisionId()
                            .equals(currentRevision.id())
                    && startedCheckpoint.revisionNumber()
                            == currentRevision.number()
                    && startedCheckpoint.lastEventSequence() == startEvent.sequence()
                    && startedCheckpoint.planState()
                            == PlanExecutionState.ACTIVE
                    && startedCheckpoint.stepStates().keySet()
                            .equals(currentStepIds)
                    && startedCheckpoint.stepStates().values().stream()
                            .allMatch(state ->
                                    state == StepExecutionState.NOT_STARTED)
                    && startedCheckpoint.receiptReferences().isEmpty()
                    && currentRevision.completedFacts().isEmpty()
                    && !startedCheckpoint.createdAt()
                            .isBefore(initialCheckpoint.createdAt());
            if (!structurallyCommitted) {
                throw failure(
                        AuthenticatedAgentTurnStepActivationCompositionCode
                                .MALFORMED_COMMITTED_START,
                        ROOT + ".executionStartInspection.value");
            }
        } catch (AuthenticatedAgentTurnStepActivationCompositionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthenticatedAgentTurnStepActivationCompositionException(
                    AuthenticatedAgentTurnStepActivationCompositionCode
                            .MALFORMED_COMMITTED_START,
                    ROOT + ".executionStartInspection.value",
                    exception);
        }
    }

    private static AuthenticatedAgentTurnStepActivationCompositionException failure(
            AuthenticatedAgentTurnStepActivationCompositionCode code,
            String path) {
        return new AuthenticatedAgentTurnStepActivationCompositionException(
                code, path);
    }
}
