package com.yanban.api.agent.v2.chain.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.api.ProductChainAnswerDeliveryProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainPlanTransitionDriver;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRecoverySource;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductChainModelProgressionTest {
    private static final Instant NOW = Instant.parse("2026-08-09T05:00:00Z");
    private static final String BODY = "perform the requested change";

    @Test
    void dispatchesExactFrozenExecutorSelectionAndReturnsSameIdentity() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        ChainPersistenceRecords.PlanBindingRecord binding = fixture.binding();
        var transition = mock(ProductChainPlanTransitionDriver.Result.class);
        when(fixture.planTransitions.recoverCompletedBinding(
                "task-1", "binding-1")).thenReturn(transition);
        fixture.frozen = new ProductChainRecoverySource.FrozenModelInput(
                fixture.instruction, binding);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "STEP_EVENT", "activation-1");

        var receipt = fixture.progression.advance(fixture.command(directive));

        assertThat(receipt.consumedSelection()).isEqualTo(
                ProductChainTaskProgressionAdapter.SelectedAction.model(
                        directive));
        verify(fixture.executor).advance(fixture.session, fixture.task,
                fixture.instruction, BODY, transition, NOW);
    }

    @Test
    void dispatchesDirectAnswerWithoutInventingAPlanIdentity() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING,
                "ROUTE_DECISION", "route-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.answer).invokeDirectAnswer(
                fixture.session, fixture.task, fixture.instruction,
                "route-1", NOW);
        verifyNoInteractions(fixture.planTransitions);
    }

    @Test
    void dispatchesPendingValidationWithTheFrozenGapIdentity() {
        Fixture fixture = new Fixture(
                ChainInstructionRelation.ANSWER_TO_PENDING_ITEM);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.PLANNER,
                ChainWorkState.VALIDATING_PENDING_ITEM,
                "PENDING_ITEM", "gap-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.pendingValidation).invoke(
                fixture.session, fixture.task, fixture.instruction,
                null, null, null, ChainRole.PLANNER, "gap-1", NOW);
    }

    @Test
    void dispatchesReflectorWithTheFrozenCandidateIdentity() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        ChainPersistenceRecords.PlanBindingRecord binding = fixture.binding();
        var transition = mock(ProductChainPlanTransitionDriver.Result.class);
        when(fixture.planTransitions.recoverCompletedBinding(
                "task-1", "binding-1")).thenReturn(transition);
        fixture.frozen = new ProductChainRecoverySource.FrozenModelInput(
                fixture.instruction, binding);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "CANDIDATE_STEP_RESULT", "candidate-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.executor).invokeReflectorReview(
                fixture.session, fixture.task, fixture.instruction,
                transition, "candidate-1", NOW);
    }

    @Test
    void dispatchesFinalizationFailureWithItsExactFormalIdentity() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "FINALIZATION_CHECK", "finalization-check-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.finalizationFailures).invoke(
                fixture.session, fixture.task, fixture.instruction,
                directive, NOW);
        verifyNoInteractions(fixture.executor);
    }

    @Test
    void dispatchesPersistentAnswerFromTheFrozenTaskOutcome() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.ANSWER, ChainWorkState.DELIVERING,
                "TASK_OUTCOME", "outcome-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.answer).invokePersistentAnswer(
                fixture.session, fixture.task, fixture.instruction, NOW);
        verifyNoInteractions(fixture.planTransitions);
    }

    @Test
    void dispatchesTerminalAnswerForBodylessCancellation() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        fixture.instruction = bodylessCancelInstruction();
        fixture.frozen = new ProductChainRecoverySource.FrozenModelInput(
                fixture.instruction, null);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.ANSWER, ChainWorkState.TERMINAL,
                "TASK_OUTCOME", "outcome-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.answer).invokePersistentAnswer(
                fixture.session, fixture.task, fixture.instruction, NOW);
        verifyNoInteractions(fixture.messages, fixture.planTransitions);
    }

    @Test
    void dispatchesPendingItemAnswerWithTheFrozenGapIdentity() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.ANSWER, ChainWorkState.WAITING_USER,
                "PENDING_ITEM", "gap-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.answer).invokePendingItemAnswer(
                fixture.session, fixture.task, fixture.instruction,
                "gap-1", NOW);
        verifyNoInteractions(fixture.planTransitions);
    }

    @Test
    void retriesPendingAnswerWithFrozenGapInsteadOfInitialInstruction() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        fixture.frozen = new ProductChainRecoverySource.FrozenModelInput(
                fixture.instruction, null, null, null,
                null, null, "gap-1", false);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.ANSWER, ChainWorkState.WAITING_USER,
                "PROPOSAL_STATE", "proposal-state-rejected-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.answer).invokePendingItemAnswer(
                fixture.session, fixture.task, fixture.instruction,
                "gap-1", NOW);
    }

    @Test
    void plansAnExplicitReplacementAsANewInitialTaskBoundary() {
        Fixture fixture = new Fixture(ChainInstructionRelation.REPLACEMENT);
        fixture.frozen = new ProductChainRecoverySource.FrozenModelInput(
                fixture.instruction, null, null, null, null, null, null, true);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "TASK", "task-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.planner).advance(
                fixture.session, fixture.task, fixture.instruction, BODY,
                ProjectChainPlannerProgression.ChainInstructionRelationValue
                        .INITIAL,
                NOW);
    }

    @Test
    void rejectsReplacementWithoutFrozenReplacementTaskAuthority() {
        Fixture fixture = new Fixture(ChainInstructionRelation.REPLACEMENT);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "TASK", "task-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> fixture.progression.advance(fixture.command(directive)))
                .hasMessage("CHAIN_PLANNER_REPLACEMENT_AUTHORITY_MISSING");

        verifyNoInteractions(fixture.planner);
    }

    @Test
    void dispatchesRecoveredPlanRevisionFromItsExactAuthority() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        var binding = fixture.binding();
        var candidate = new ProductChainRecoverySource.FrozenCandidateInput(
                "workspace-1", 41L, "a".repeat(64));
        fixture.frozen = new ProductChainRecoverySource.FrozenModelInput(
                fixture.instruction, binding, null, candidate);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "REVIEW_DECISION", "review-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.planner).advanceRevision(
                fixture.session, fixture.task, fixture.instruction, BODY,
                binding, new ProjectChainPlannerProgression.RevisionCandidate(
                        "workspace-1", 41L, "a".repeat(64)),
                "REVIEW_DECISION", "review-1", NOW);
    }

    @Test
    void dispatchesPlanRevisionWithoutCandidateWhenNoneExists() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        var binding = fixture.binding();
        fixture.frozen = new ProductChainRecoverySource.FrozenModelInput(
                fixture.instruction, binding);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "REVIEW_DECISION", "review-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.planner).advanceRevision(
                fixture.session, fixture.task, fixture.instruction, BODY,
                binding, null, "REVIEW_DECISION", "review-1", NOW);
    }

    @Test
    void dispatchesPersistentRouteWithoutAPlanToPlannerOwner() {
        Fixture fixture = new Fixture(ChainInstructionRelation.INITIAL);
        var directive = new ChainRecoveryRuntime.NextDirective(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "ROUTE_DECISION", "route-1");

        fixture.progression.advance(fixture.command(directive));

        verify(fixture.planner).advancePersistentPlan(
                fixture.session, fixture.task, fixture.instruction, BODY,
                "route-1", NOW);
    }

    private static final class Fixture {
        private final ProductChainFoundationRepositoryAdapter foundations =
                mock(ProductChainFoundationRepositoryAdapter.class);
        private final AgentSessionRepository sessions =
                mock(AgentSessionRepository.class);
        private final AgentMessageRepository messages =
                mock(AgentMessageRepository.class);
        private final ProjectChainPlannerProgression planner =
                mock(ProjectChainPlannerProgression.class);
        private final ProductChainPlanTransitionDriver planTransitions =
                mock(ProductChainPlanTransitionDriver.class);
        private final ProductChainExecutorProgression executor =
                mock(ProductChainExecutorProgression.class);
        private final ProductChainAnswerDeliveryProgression answer =
                mock(ProductChainAnswerDeliveryProgression.class);
        private final ProductChainPendingItemModelInvoker pendingValidation =
                mock(ProductChainPendingItemModelInvoker.class);
        private final ProductChainFinalizationFailureProgression
                finalizationFailures = mock(
                ProductChainFinalizationFailureProgression.class);
        private final AgentSession session = mock(AgentSession.class);
        private final ChainPersistenceRecords.TaskRecord task = task();
        private ChainPersistenceRecords.InstructionRecord instruction;
        private ProductChainRecoverySource.FrozenModelInput frozen;
        private final ProductChainModelProgression progression;

        private Fixture(ChainInstructionRelation relation) {
            instruction = instruction(relation);
            frozen = new ProductChainRecoverySource.FrozenModelInput(
                    instruction, null);
            AgentMessage message = mock(AgentMessage.class);
            when(foundations.findTask("task-1"))
                    .thenReturn(Optional.of(task));
            when(sessions.findByIdAndUserId(2L, 1L))
                    .thenReturn(Optional.of(session));
            when(session.getProjectId()).thenReturn(3L);
            when(messages.findById(5L)).thenReturn(Optional.of(message));
            when(message.getSessionId()).thenReturn(2L);
            when(message.getUserId()).thenReturn(1L);
            when(message.getContent()).thenReturn(BODY);
            progression = new ProductChainModelProgression(
                    foundations, sessions, messages, planner,
                    planTransitions, executor, answer, pendingValidation,
                    finalizationFailures,
                    (snapshot, directive) -> frozen,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private ProductChainTaskProgressionAdapter.ModelCommand command(
                ChainRecoveryRuntime.NextDirective directive) {
            var command = mock(
                    ProductChainTaskProgressionAdapter.ModelCommand.class);
            when(command.taskId()).thenReturn("task-1");
            when(command.directive()).thenReturn(directive);
            when(command.snapshot()).thenReturn(mock(
                    ChainRecoveryRuntime.RecoverySnapshot.class));
            return command;
        }

        private ChainPersistenceRecords.PlanBindingRecord binding() {
            return new ChainPersistenceRecords.PlanBindingRecord(
                    "binding-1", "task-1", "binding-event-1",
                    "instruction-1", "route-1", "frame-1", "plan-1",
                    "revision-1", 1, "MODEL_PROPOSAL", "proposal-1",
                    sha256("authority"), "transition-1", NOW.minusSeconds(1));
        }
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                1, 2, 4, 5L, "client-1", sha256("request"),
                3L, "version-1", 7, NOW.minusSeconds(10));
    }

    private static ChainPersistenceRecords.InstructionRecord instruction(
            ChainInstructionRelation relation) {
        return new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-1", 2, "task-1", 5L,
                sha256(BODY), "message-key-1", relation,
                relation == ChainInstructionRelation.REPLACEMENT
                        ? "instruction-old" : null,
                relation == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM
                        ? "gap-1" : null,
                sha256("boundary"), NOW.minusSeconds(9));
    }

    private static ChainPersistenceRecords.InstructionRecord
            bodylessCancelInstruction() {
        return new ChainPersistenceRecords.InstructionRecord(
                "instruction-cancel", "command-cancel", 2, "task-1",
                null, null, "command:cancel", ChainInstructionRelation.CANCEL,
                "instruction-1", null, sha256("cancel-boundary"),
                NOW.minusSeconds(1));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
