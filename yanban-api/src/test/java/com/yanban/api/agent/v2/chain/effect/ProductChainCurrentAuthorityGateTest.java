package com.yanban.api.agent.v2.chain.effect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPersistenceRecords.BindingRole;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.step.ChainStepCommitGate;
import io.paperagent.v2.chain.step.ChainStepCommitGate.GateQuery;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductChainCurrentAuthorityGateTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void acceptsCurrentLaterStepThroughBindingAndCompletedRevisionAuthority() {
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        ChainFinalizationRepository finalization = mock(
                ChainFinalizationRepository.class);
        StepRecoveryRepository recovery = mock(StepRecoveryRepository.class);
        ChainStepAuthorityPort steps = mock(ChainStepAuthorityPort.class);

        var task = new ChainPersistenceRecords.TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                7L, 9L, 42L, null, "client.1", HASH,
                8L, "project-version.1", 2L, NOW);
        var instruction = new ChainPersistenceRecords.InstructionRecord(
                "instruction.1", "command.1", 9L, "task.1", 1L,
                HASH, "message.1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
        var instructionBinding =
                new ChainPersistenceRecords.TaskInstructionBindingRecord(
                        "task.1", "event.instruction.1", "instruction.1",
                        1L, BindingRole.ORIGIN, NOW);
        var planBinding = new ChainPersistenceRecords.PlanBindingRecord(
                "binding.1", "task.1", "event.plan.1", "instruction.1",
                "route.1", "task-frame.1", "plan.1", "revision.1",
                1L, "PLANNER_PROPOSAL", "proposal.1", HASH,
                "transition.plan.1", NOW);
        var authorityEvents = List.of(
                new ChainPersistenceRecords.AuthorityEventRecord(
                        "event.instruction.1", "task.1", 1L,
                        "INSTRUCTION_BOUND", null, HASH, NOW),
                new ChainPersistenceRecords.AuthorityEventRecord(
                        "event.plan.1", "task.1", 2L,
                        "PLAN_BINDING", "transition.plan.1", HASH, NOW));
        when(foundations.findTask("task.1")).thenReturn(Optional.of(task));
        when(foundations.findTaskInstructions("task.1", Long.MAX_VALUE))
                .thenReturn(List.of(instructionBinding));
        when(foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(2L);
        when(foundations.findAuthorityEvents("task.1", 2L))
                .thenReturn(authorityEvents);
        when(foundations.findInstruction("instruction.1"))
                .thenReturn(Optional.of(instruction));
        when(finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.empty());
        when(workflow.findPendingItems("task.1")).thenReturn(List.of());
        when(workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(planBinding));

        var completedRevision = new ChainStepAuthorityPort.PlanSnapshot(
                "task.1", "task-frame.1", "plan.1", "revision.2",
                "candidate.1", "instruction.1",
                List.of(new ChainStepAuthorityPort.StepDefinition(
                        "step.2", 2, Set.of("step.1"))));
        var completion = new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        "completion.1", "task.1", "revision.1", "step.1",
                        "activation.1", ChainStepAuthorityPort.StepEventKind.COMPLETED,
                        "decision.1", "transition.step.1", NOW),
                4L);
        var activationOnBinding = new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        "activation.2", "task.1", "revision.1", "step.2",
                        "activation.2", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                        "decision.1", "transition.step.1", NOW),
                5L);
        var activationOnCompleted = new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        "activation.2", "task.1", "revision.2", "step.2",
                        "activation.2", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                        "decision.1", "transition.step.1", NOW),
                5L);
        when(steps.findPlan("task.1", "revision.2"))
                .thenReturn(Optional.of(completedRevision));
        when(steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(completion, activationOnBinding));
        when(steps.findStepEvents("task.1", "revision.2"))
                .thenReturn(List.of(completion, activationOnCompleted));

        PlanId planId = new PlanId("plan.1");
        PersistedStepRecoveryActive active = mock(
                PersistedStepRecoveryActive.class);
        Plan plan = mock(Plan.class);
        PlanRevision revision = mock(PlanRevision.class);
        PersistedStepActivation activation = mock(
                PersistedStepActivation.class);
        EventEnvelope activationEvent = mock(EventEnvelope.class);
        when(active.plan()).thenReturn(plan);
        when(active.activation()).thenReturn(activation);
        when(plan.id()).thenReturn(planId);
        when(plan.taskFrameId()).thenReturn(new TaskFrameId("task-frame.1"));
        when(plan.latestRevision()).thenReturn(revision);
        when(revision.id()).thenReturn(new PlanRevisionId("revision.2"));
        when(activation.stepId()).thenReturn(new PlanStepId("step.2"));
        when(activation.activationEvent()).thenReturn(activationEvent);
        when(activationEvent.id()).thenReturn(new EventId("activation.2"));
        when(recovery.inspect(planId)).thenReturn(PersistenceResult.found(active));

        var gate = new ProductChainCurrentAuthorityGate(
                foundations, workflow, finalization, recovery, steps);
        var bindingRevisionQuery = new GateQuery(
                ChainStepCommitGate.CommitKind.ACTION_BINDING,
                "task.1", "instruction.1", "task-frame.1", "plan.1",
                "revision.1", "step.2", "activation.2");
        var completedRevisionQuery = new GateQuery(
                ChainStepCommitGate.CommitKind.ACTION_BINDING,
                "task.1", "instruction.1", "task-frame.1", "plan.1",
                "revision.2", "step.2", "activation.2");

        assertDoesNotThrow(() -> gate.requireCurrent(bindingRevisionQuery));
        assertDoesNotThrow(() -> gate.requireCurrent(completedRevisionQuery));
        verify(steps).findPlan("task.1", "revision.2");
        verify(steps, atLeastOnce()).findStepEvents("task.1", "revision.1");
        verify(steps, atLeastOnce()).findStepEvents("task.1", "revision.2");
    }

    @Test
    void acceptsFinalReadinessFromBoundRevisionAfterCompletionCheckpoints() {
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        ChainFinalizationRepository finalization = mock(
                ChainFinalizationRepository.class);
        StepRecoveryRepository recovery = mock(StepRecoveryRepository.class);
        ChainStepAuthorityPort steps = mock(ChainStepAuthorityPort.class);

        var task = new ChainPersistenceRecords.TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                7L, 9L, 42L, null, "client.1", HASH,
                8L, "project-version.1", 2L, NOW);
        var instruction = new ChainPersistenceRecords.InstructionRecord(
                "instruction.1", "command.1", 9L, "task.1", 1L,
                HASH, "message.1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
        var instructionBinding =
                new ChainPersistenceRecords.TaskInstructionBindingRecord(
                        "task.1", "event.instruction.1", "instruction.1",
                        1L, BindingRole.ORIGIN, NOW);
        var planBinding = new ChainPersistenceRecords.PlanBindingRecord(
                "binding.1", "task.1", "event.plan.1", "instruction.1",
                "route.1", "task-frame.1", "plan.1", "revision.1",
                1L, "PLANNER_PROPOSAL", "proposal.1", HASH,
                "transition.plan.1", NOW);
        when(foundations.findTask("task.1")).thenReturn(Optional.of(task));
        when(foundations.findTaskInstructions("task.1", Long.MAX_VALUE))
                .thenReturn(List.of(instructionBinding));
        when(foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(2L);
        when(foundations.findAuthorityEvents("task.1", 2L)).thenReturn(List.of(
                new ChainPersistenceRecords.AuthorityEventRecord(
                        "event.instruction.1", "task.1", 1L,
                        "INSTRUCTION_BOUND", null, HASH, NOW),
                new ChainPersistenceRecords.AuthorityEventRecord(
                        "event.plan.1", "task.1", 2L,
                        "PLAN_BINDING", "transition.plan.1", HASH, NOW)));
        when(foundations.findInstruction("instruction.1"))
                .thenReturn(Optional.of(instruction));
        when(finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.empty());
        when(workflow.findPendingItems("task.1")).thenReturn(List.of());
        when(workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(planBinding));

        PlanRevision bound = mock(PlanRevision.class);
        PlanRevision firstCompletion = mock(PlanRevision.class);
        PlanRevision finalCompletion = mock(PlanRevision.class);
        PlanRevisionId boundId = new PlanRevisionId("revision.1");
        PlanRevisionId firstCompletionId = new PlanRevisionId("revision.2");
        PlanRevisionId finalCompletionId = new PlanRevisionId("revision.3");
        TaskFrameId taskFrameId = new TaskFrameId("task-frame.1");
        PlanStepId firstStepId = new PlanStepId("step.1");
        PlanStepId finalStepId = new PlanStepId("step.2");
        CompletionFact firstFact = mock(CompletionFact.class);
        CompletionFact finalFact = mock(CompletionFact.class);
        when(bound.id()).thenReturn(boundId);
        when(bound.number()).thenReturn(1L);
        when(bound.taskFrameId()).thenReturn(taskFrameId);
        when(bound.steps()).thenReturn(List.of());
        when(bound.completedFacts()).thenReturn(Map.of());
        when(firstCompletion.id()).thenReturn(firstCompletionId);
        when(firstCompletion.parentRevisionId())
                .thenReturn(Optional.of(boundId));
        when(firstCompletion.number()).thenReturn(2L);
        when(firstCompletion.taskFrameId()).thenReturn(taskFrameId);
        when(firstCompletion.steps()).thenReturn(List.of());
        when(firstCompletion.completedFacts())
                .thenReturn(Map.of(firstStepId, firstFact));
        when(finalCompletion.id()).thenReturn(finalCompletionId);
        when(finalCompletion.parentRevisionId())
                .thenReturn(Optional.of(firstCompletionId));
        when(finalCompletion.number()).thenReturn(3L);
        when(finalCompletion.taskFrameId()).thenReturn(taskFrameId);
        when(finalCompletion.steps()).thenReturn(List.of());
        when(finalCompletion.completedFacts()).thenReturn(Map.of(
                firstStepId, firstFact, finalStepId, finalFact));

        Plan plan = mock(Plan.class);
        PlanId planId = new PlanId("plan.1");
        when(plan.id()).thenReturn(planId);
        when(plan.taskFrameId()).thenReturn(taskFrameId);
        when(plan.revisions()).thenReturn(List.of(
                bound, firstCompletion, finalCompletion));
        when(plan.latestRevision()).thenReturn(finalCompletion);
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.stepStates()).thenReturn(Map.of(
                firstStepId, StepExecutionState.SUCCEEDED,
                finalStepId, StepExecutionState.SUCCEEDED));
        VersionedCheckpoint versioned = mock(VersionedCheckpoint.class);
        when(versioned.checkpoint()).thenReturn(checkpoint);
        PersistedStepRecoverySucceeded succeeded = mock(
                PersistedStepRecoverySucceeded.class);
        when(succeeded.plan()).thenReturn(plan);
        when(succeeded.checkpoint()).thenReturn(versioned);
        when(recovery.inspect(planId))
                .thenReturn(PersistenceResult.found(succeeded));
        when(steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(new ChainStepAuthorityPort.StepEvent(
                        new ChainStepAuthorityPort.StepEventCommand(
                                "completion.2", "task.1", "revision.1",
                                "step.2", "activation.2",
                                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                                "decision.2", "transition.2", NOW),
                        6L)));

        var gate = new ProductChainCurrentAuthorityGate(
                foundations, workflow, finalization, recovery, steps);
        var query = new GateQuery(
                ChainStepCommitGate.CommitKind.FINALIZATION_READINESS,
                "task.1", "instruction.1", "task-frame.1", "plan.1",
                "revision.1", "step.2", "activation.2");

        assertDoesNotThrow(() -> gate.requireCurrent(query));
        when(finalCompletion.steps()).thenReturn(List.of(mock(PlanStep.class)));
        assertThrows(IllegalStateException.class,
                () -> gate.requireCurrent(query));
    }
}
