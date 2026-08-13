package com.yanban.api.agent.v2.chain.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnPlanExecutionContextComposer;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import io.paperagent.v2.persistence.LeaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductChainPlanTransitionDriverTest {
    private static final Instant NOW = Instant.parse("2026-08-09T08:00:00Z");
    private static final String HASH = "0".repeat(64);

    @Test
    void recoversTheUniqueCompletedFormalResultWithoutWriting() {
        Fixture fixture = new Fixture();
        Graph graph = fixture.graph("one", true, "source-one");
        when(fixture.workflow.findPlanBindings("task-1"))
                .thenReturn(List.of(graph.binding()));

        var result = fixture.driver.recoverCompleted("task-1");

        assertEquals(graph.transition().transitionId(), result.transitionId());
        assertSame(graph.binding(), result.planBinding());
        assertEquals(graph.completeEventId(), result.completeEventId());
        assertSame(graph.activation(), result.firstStepEvent());
        verify(fixture.foundations).findTask("task-1");
        verify(fixture.workflow).findPlanBindings("task-1");
        verify(fixture.workflow).findTransition(
                graph.transition().transitionId());
        verify(fixture.workflow).findTransitionStages(
                graph.transition().transitionId());
        verify(fixture.steps).findStepEvents(
                "task-1", graph.binding().planRevisionId());
        verifyNoMoreInteractions(
                fixture.foundations, fixture.workflow, fixture.steps);
        verifyNoInteractions(
                fixture.models, fixture.finalization, fixture.plans,
                fixture.executionStarts, fixture.contexts,
                fixture.executionContexts, fixture.leases);
    }

    @Test
    void rejectsCrossTaskBinding() {
        Fixture fixture = new Fixture();
        var foreign = mock(ChainPersistenceRecords.PlanBindingRecord.class);
        when(foreign.taskId()).thenReturn("task-2");
        when(fixture.workflow.findPlanBindings("task-1"))
                .thenReturn(List.of(foreign));

        assertThrows(IllegalStateException.class,
                () -> fixture.driver.recoverCompleted("task-1"));
    }

    @Test
    void rejectsTwoCompletedPlanChangeResults() {
        Fixture fixture = new Fixture();
        Graph first = fixture.graph("one", true, "source-one");
        Graph second = fixture.graph("two", true, "source-two");
        when(fixture.workflow.findPlanBindings("task-1"))
                .thenReturn(List.of(first.binding(), second.binding()));

        assertThrows(IllegalStateException.class,
                () -> fixture.driver.recoverCompleted("task-1"));
    }

    @Test
    void rejectsAnIncompletePlanChange() {
        Fixture fixture = new Fixture();
        Graph graph = fixture.graph("incomplete", false, "source-one");
        when(fixture.workflow.findPlanBindings("task-1"))
                .thenReturn(List.of(graph.binding()));

        assertThrows(IllegalStateException.class,
                () -> fixture.driver.recoverCompleted("task-1"));
    }

    @Test
    void rejectsActivationFromAnotherSource() {
        Fixture fixture = new Fixture();
        Graph graph = fixture.graph("drift", true, "wrong-source");
        when(fixture.workflow.findPlanBindings("task-1"))
                .thenReturn(List.of(graph.binding()));

        assertThrows(IllegalStateException.class,
                () -> fixture.driver.recoverCompleted("task-1"));
    }

    @Test
    void recoveryCommitsMissingFirstStepOfAFormalRevision() {
        Fixture fixture = new Fixture();
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.PLAN_CHANGE, "task-1", "review-1", HASH)
                .transitionId();
        var transition = new ChainPersistenceRecords.TransitionRecord(
                transitionId, "task-1", "transition-event-1",
                ChainTransitionType.PLAN_CHANGE, "review-1", HASH, NOW);
        var binding = mock(ChainPersistenceRecords.PlanBindingRecord.class);
        when(binding.taskId()).thenReturn("task-1");
        when(binding.transitionId()).thenReturn(transitionId);
        when(binding.planRevisionId()).thenReturn("revision-2");
        when(binding.planRevisionNumber()).thenReturn(2L);
        when(fixture.workflow.findTransition(transitionId))
                .thenReturn(Optional.of(transition));
        when(fixture.workflow.findPlanBindings("task-1"))
                .thenReturn(List.of(binding));
        when(fixture.steps.findPlan("task-1", "revision-2"))
                .thenReturn(Optional.of(new ChainStepAuthorityPort.PlanSnapshot(
                        "task-1", "task-frame-1", "plan-1", "revision-2",
                        "candidate-1", "instruction-version-1",
                        List.of(new ChainStepAuthorityPort.StepDefinition(
                                "replacement-step", 1, java.util.Set.of())))));
        when(fixture.steps.findStepEvents("task-1", "revision-2"))
                .thenReturn(List.of());
        when(fixture.foundations.highestAuthorityEventSequence("task-1"))
                .thenReturn(0L);
        when(fixture.foundations.findAuthorityEvents("task-1", 0L))
                .thenReturn(List.of());
        when(fixture.workflow.findCandidateStepResults("task-1"))
                .thenReturn(List.of());
        when(fixture.workflow.findReviewDecisions("task-1"))
                .thenReturn(List.of());
        when(fixture.workflow.findOpenPendingItems("task-1"))
                .thenReturn(List.of());
        when(fixture.steps.appendStepEvent(
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ChainStepAuthorityPort.StepEventCommand command =
                            invocation.getArgument(0);
                    return new ChainPersistenceRecords.AppendResult<>(
                            new ChainStepAuthorityPort.StepEvent(command, 1L),
                            false);
                });

        var recovered = fixture.driver.recoverCommittedStage(
                new ChainCompositeTransitionRuntime.StageCommand(
                        transition, ChainTransitionStage.NEW_STEP_ACTIVATED, 4));

        assertEquals("STEP_EVENT", recovered.successorAuthorityType());
        assertEquals("step.activation."
                        + sha256("task-1\0revision-2\0replacement-step\0"
                        + transitionId),
                recovered.successorAuthorityRef());
        verifyNoInteractions(fixture.executionStarts,
                fixture.executionContexts, fixture.leases);
    }

    private static final class Fixture {
        private final ProductChainFoundationRepositoryAdapter foundations =
                mock(ProductChainFoundationRepositoryAdapter.class);
        private final ProductChainModelRepositoryAdapter models =
                mock(ProductChainModelRepositoryAdapter.class);
        private final ProductChainWorkflowRepositoryAdapter workflow =
                mock(ProductChainWorkflowRepositoryAdapter.class);
        private final ProductChainFinalizationRepositoryAdapter finalization =
                mock(ProductChainFinalizationRepositoryAdapter.class);
        private final ProductChainPlanCommitAdapter plans =
                mock(ProductChainPlanCommitAdapter.class);
        private final ProductChainStepAuthorityAdapter steps =
                mock(ProductChainStepAuthorityAdapter.class);
        private final ProductChainExecutionStartAdapter executionStarts =
                mock(ProductChainExecutionStartAdapter.class);
        private final ProductChainContextRepositoryAdapter contexts =
                mock(ProductChainContextRepositoryAdapter.class);
        private final AuthenticatedAgentTurnPlanExecutionContextComposer
                executionContexts = mock(
                AuthenticatedAgentTurnPlanExecutionContextComposer.class);
        private final LeaseRepository leases = mock(LeaseRepository.class);
        private final ProductChainPlanTransitionDriver driver;

        private Fixture() {
            var task = mock(ChainPersistenceRecords.TaskRecord.class);
            when(task.taskId()).thenReturn("task-1");
            when(foundations.findTask("task-1"))
                    .thenReturn(Optional.of(task));
            driver = new ProductChainPlanTransitionDriver(
                    foundations, models, workflow, finalization, plans, steps,
                    executionStarts, contexts, executionContexts, leases);
        }

        private Graph graph(
                String suffix, boolean complete, String activationSource) {
            String source = "source-" + suffix;
            String transitionId = new ChainIdentity.Transition(
                    ChainTransitionType.PLAN_CHANGE, "task-1", source, HASH)
                    .transitionId();
            var transition = new ChainPersistenceRecords.TransitionRecord(
                    transitionId, "task-1", "transition-event-" + suffix,
                    ChainTransitionType.PLAN_CHANGE, source, HASH, NOW);
            var binding = mock(
                    ChainPersistenceRecords.PlanBindingRecord.class);
            when(binding.planBindingId()).thenReturn("binding-" + suffix);
            when(binding.taskId()).thenReturn("task-1");
            when(binding.transitionId()).thenReturn(transitionId);
            when(binding.planRevisionId()).thenReturn("revision-" + suffix);
            String activationId = "activation-" + suffix;
            var activation = new ChainStepAuthorityPort.StepEvent(
                    new ChainStepAuthorityPort.StepEventCommand(
                            activationId, "task-1", "revision-" + suffix,
                            "step-1", activationId,
                            ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                            activationSource, transitionId, NOW), 1);
            List<ChainPersistenceRecords.TransitionStageRecord> stages =
                    stages(transition, binding.planBindingId(), activationId,
                            complete);
            when(workflow.findTransition(transitionId))
                    .thenReturn(Optional.of(transition));
            when(workflow.findTransitionStages(transitionId))
                    .thenReturn(stages);
            when(steps.findStepEvents(
                    "task-1", "revision-" + suffix))
                    .thenReturn(List.of(activation));
            return new Graph(transition, binding, activation,
                    stages.get(stages.size() - 1).eventId());
        }

        private static List<ChainPersistenceRecords.TransitionStageRecord>
                stages(
                        ChainPersistenceRecords.TransitionRecord transition,
                        String bindingId,
                        String activationId,
                        boolean complete) {
            var values = new java.util.ArrayList<
                    ChainPersistenceRecords.TransitionStageRecord>();
            values.add(stage(transition, ChainTransitionStage.OPEN, 0,
                    null, null));
            values.add(stage(transition,
                    ChainTransitionStage.TASKFRAME_PLAN_COMMITTED, 1,
                    "PLAN_BINDING", bindingId));
            values.add(stage(transition,
                    ChainTransitionStage.APPLICABILITY_COMMITTED, 2,
                    null, null));
            values.add(stage(transition,
                    ChainTransitionStage.OLD_STEP_SUPERSEDED_OR_NONE, 3,
                    null, null));
            values.add(stage(transition,
                    ChainTransitionStage.NEW_STEP_ACTIVATED, 4,
                    "STEP_EVENT", activationId));
            if (complete) {
                values.add(stage(transition, ChainTransitionStage.COMPLETE, 5,
                        null, null));
            }
            return List.copyOf(values);
        }

        private static ChainPersistenceRecords.TransitionStageRecord stage(
                ChainPersistenceRecords.TransitionRecord transition,
                ChainTransitionStage code,
                int ordinal,
                String successorType,
                String successorRef) {
            return new ChainPersistenceRecords.TransitionStageRecord(
                    transition.transitionId(), code, transition.taskId(),
                    "stage-event-" + transition.sourceDecisionId()
                            + "-" + ordinal,
                    ordinal, null, null, successorType, successorRef, NOW);
        }
    }

    private record Graph(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.PlanBindingRecord binding,
            ChainStepAuthorityPort.StepEvent activation,
            String completeEventId) {
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
