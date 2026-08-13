package com.yanban.api.agent.v2.chain.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.api.ProductChainAnswerDeliveryProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorPump;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductChainMechanicalProposalProgressionTest {
    private static final Instant NOW = Instant.parse("2026-08-09T03:00:00Z");

    @Test
    void dispatchesTheExactAcceptedExecutorProposal() {
        Fixture fixture = new Fixture(
                ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_TOOL_ACTION,
                "accepted-event-1");
        when(fixture.executor.consumeAcceptedProposal(
                "task-1", "proposal-1", NOW)).thenReturn(
                new ProductChainExecutorPump.OfficialSuccessor(
                        "ACTION_BINDING", "action-1"));

        var receipt = fixture.progression.advance(fixture.command());

        assertThat(receipt.consumedSelection()).isEqualTo(
                ProductChainTaskProgressionAdapter.SelectedAction.mechanical(
                        "MODEL_PROPOSAL", "proposal-1"));
        verify(fixture.executor).consumeAcceptedProposal(
                "task-1", "proposal-1", NOW);
    }

    @Test
    void rejectsASelectorThatDoesNotNameTheAcceptedStateEvent() {
        Fixture fixture = new Fixture(
                ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_TOOL_ACTION,
                "another-event");

        assertThatThrownBy(() -> fixture.progression.advance(
                fixture.command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_MECHANICAL_PROPOSAL_SELECTION_STALE");
    }

    @Test
    void dispatchesTheExactAcceptedReflectorProposal() {
        Fixture fixture = new Fixture(
                ChainRole.REFLECTOR,
                ChainProposalKind.REFLECTOR_CONTINUE_STEP,
                "accepted-event-1");

        fixture.progression.advance(fixture.command());

        verify(fixture.executor).consumeAcceptedReflectorProposal(
                fixture.task, fixture.instruction, "proposal-1", NOW);
    }

    @Test
    void dispatchesAcceptedFinalizationFailureReviewToItsFormalOwner() {
        Fixture fixture = new Fixture(
                ChainRole.REFLECTOR,
                ChainProposalKind.REFLECTOR_TASK_FAILED,
                "accepted-event-1",
                ChainWorkState.AWAITING_REVIEW,
                ProductChainFinalizationFailureProgression.CALL_REASON);

        fixture.progression.advance(fixture.command());

        verify(fixture.finalizationFailures).consume(
                fixture.task, fixture.instruction, "proposal-1", NOW);
    }

    @Test
    void dispatchesTheExactAcceptedPersistentAnswerProposal() {
        Fixture fixture = new Fixture(
                ChainRole.ANSWER,
                ChainProposalKind.ANSWER_FINAL_DELIVERY,
                "accepted-event-1");

        fixture.progression.advance(fixture.command());

        verify(fixture.answer).consumeAcceptedPersistent(
                fixture.task, fixture.instruction, "proposal-1", NOW);
    }

    @Test
    void dispatchesTheExactAcceptedPendingItemAnswerProposal() {
        Fixture fixture = new Fixture(
                ChainRole.ANSWER,
                ChainProposalKind.ANSWER_USER_QUESTION,
                "accepted-event-1");

        fixture.progression.advance(fixture.command());

        verify(fixture.answer).consumeAcceptedPendingItem(
                fixture.task, fixture.instruction, "gap-1",
                "proposal-1", NOW);
    }

    @Test
    void consumesAcceptedPendingValidationThroughItsFormalOwner() {
        Fixture fixture = new Fixture(
                ChainRole.PLANNER,
                ChainProposalKind.PLANNER_DIRECT_ROUTE,
                "accepted-event-1",
                ChainWorkState.VALIDATING_PENDING_ITEM);

        fixture.progression.advance(fixture.command());

        verify(fixture.pendingValidation).consume(
                "task-1", "proposal-1", NOW);
    }

    private static final class Fixture {
        private final ProductChainFoundationRepositoryAdapter foundations =
                mock(ProductChainFoundationRepositoryAdapter.class);
        private final ProductChainModelRepositoryAdapter models =
                mock(ProductChainModelRepositoryAdapter.class);
        private final ProductChainWorkflowRepositoryAdapter workflow =
                mock(ProductChainWorkflowRepositoryAdapter.class);
        private final ProjectChainPlannerProgression planner =
                mock(ProjectChainPlannerProgression.class);
        private final ProductChainExecutorProgression executor =
                mock(ProductChainExecutorProgression.class);
        private final ProductChainAnswerDeliveryProgression answer =
                mock(ProductChainAnswerDeliveryProgression.class);
        private final ProductChainPendingItemValidationProgression
                pendingValidation = mock(
                ProductChainPendingItemValidationProgression.class);
        private final ProductChainFinalizationFailureProgression
                finalizationFailures = mock(
                ProductChainFinalizationFailureProgression.class);
        private final ProductChainMechanicalProposalProgression progression;
        private final ProductChainNextRoleSelector.MechanicalProposal selected;
        private final ChainPersistenceRecords.TaskRecord task;
        private final ChainPersistenceRecords.InstructionRecord instruction;

        private Fixture(
                ChainRole role, ChainProposalKind kind,
                String selectedAcceptedEventId) {
            this(role, kind, selectedAcceptedEventId,
                    role == ChainRole.EXECUTOR
                            ? ChainWorkState.EXECUTING
                            : role == ChainRole.REFLECTOR
                            ? ChainWorkState.AWAITING_REVIEW
                            : ChainWorkState.DELIVERING,
                    "test");
        }

        private Fixture(
                ChainRole role, ChainProposalKind kind,
                String selectedAcceptedEventId,
                ChainWorkState workState) {
            this(role, kind, selectedAcceptedEventId, workState, "test");
        }

        private Fixture(
                ChainRole role, ChainProposalKind kind,
                String selectedAcceptedEventId,
                ChainWorkState workState,
                String callReason) {
            task = mock(ChainPersistenceRecords.TaskRecord.class);
            when(task.taskId()).thenReturn("task-1");
            when(task.sessionId()).thenReturn(1L);
            when(task.nextEventSequence()).thenReturn(7L);
            instruction = mock(
                    ChainPersistenceRecords.InstructionRecord.class);
            when(instruction.instructionId()).thenReturn("instruction-1");
            when(instruction.sessionId()).thenReturn(1L);
            var proposal = mock(
                    ChainPersistenceRecords.ModelProposalRecord.class);
            when(proposal.proposalId()).thenReturn("proposal-1");
            when(proposal.invocationId()).thenReturn("invocation-1");
            when(proposal.taskId()).thenReturn("task-1");
            when(proposal.role()).thenReturn(role);
            when(proposal.proposalKind()).thenReturn(kind);
            when(foundations.findTask("task-1")).thenReturn(
                    Optional.of(task));
            when(foundations.findTaskInstructions("task-1", 7L))
                    .thenReturn(List.of(
                            new ChainPersistenceRecords
                                    .TaskInstructionBindingRecord(
                                    "task-1", "instruction-event-1",
                                    "instruction-1", 1,
                                    ChainPersistenceRecords.BindingRole.ORIGIN,
                                    NOW.minusSeconds(2))));
            when(foundations.findInstruction("instruction-1"))
                    .thenReturn(Optional.of(instruction));
            when(models.findProposal("proposal-1"))
                    .thenReturn(Optional.of(proposal));
            when(models.findInvocation("invocation-1")).thenReturn(
                    Optional.of(new ChainPersistenceRecords
                            .ModelInvocationRecord(
                            "invocation-1", "task-1", "context-1",
                            "completion-1", role,
                            workState,
                            callReason, "provider", "model", 1,
                            "chain-runtime-policy-v1",
                            NOW.minusSeconds(1))));
            when(models.findProposalStateEvents("proposal-1"))
                    .thenReturn(List.of(
                            new ChainPersistenceRecords
                                    .ProposalStateEventRecord(
                                    "proposal-1", 1, "task-1",
                                    "accepted-event-1",
                                    ChainProposalState.ACCEPTED,
                                    null, null, NOW.minusSeconds(1))));
            if (kind == ChainProposalKind.ANSWER_USER_QUESTION) {
                var pending = mock(
                        ChainPersistenceRecords.PendingItemRecord.class);
                when(pending.gapId()).thenReturn("gap-1");
                when(workflow.findPendingItems("task-1"))
                        .thenReturn(List.of(pending));
                when(workflow.findPendingItemEvents("gap-1"))
                        .thenReturn(List.of());
            }
            selected = new ProductChainNextRoleSelector.MechanicalProposal(
                    "proposal-1", role, kind,
                    selectedAcceptedEventId);
            progression = new ProductChainMechanicalProposalProgression(
                    foundations, models, workflow, planner, executor, answer,
                    pendingValidation,
                    finalizationFailures,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private ProductChainTaskProgressionAdapter.MechanicalCommand command() {
            var command = mock(
                    ProductChainTaskProgressionAdapter.MechanicalCommand.class);
            when(command.taskId()).thenReturn("task-1");
            when(command.selection()).thenReturn(selected);
            return command;
        }
    }
}
