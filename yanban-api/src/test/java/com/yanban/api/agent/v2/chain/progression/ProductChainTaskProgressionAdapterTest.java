package com.yanban.api.agent.v2.chain.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRecoveryCoordinator;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.finalization.ChainFinalizationRuntime;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductChainTaskProgressionAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-09T02:00:00Z");

    @Test
    void dispatchesOneExactModelSelection() {
        var directive = directive(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "STEP_EVENT", "activation-1");
        List<ProductChainTaskProgressionAdapter.ModelCommand> models =
                new ArrayList<>();
        List<ProductChainTaskProgressionAdapter.MechanicalCommand> mechanical =
                new ArrayList<>();
        var adapter = adapter(runtime(directive), command -> {
            models.add(command);
            return receipt(ProductChainTaskProgressionAdapter
                    .SelectedAction.model(command.directive()));
        }, command -> {
            mechanical.add(command);
            return receipt(ProductChainTaskProgressionAdapter
                    .SelectedAction.mechanical("unused", "unused"));
        });

        adapter.advance("task-1", claim());

        assertThat(models).hasSize(1);
        assertThat(models.get(0).taskId()).isEqualTo("task-1");
        assertThat(models.get(0).directive()).isEqualTo(directive);
        assertThat(models.get(0).snapshot().roleProjection().authorityCut())
                .isEqualTo(7);
        assertThat(mechanical).isEmpty();
    }

    @Test
    void rejectsAnOwnerThatConsumesAnotherSelectorIdentity() {
        var directive = directive(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "CANDIDATE_STEP_RESULT", "candidate-1");
        var adapter = adapter(runtime(directive), command -> receipt(
                        ProductChainTaskProgressionAdapter.SelectedAction.model(
                                directive(ChainRole.REFLECTOR,
                                        ChainWorkState.AWAITING_REVIEW,
                                        "CANDIDATE_STEP_RESULT", "candidate-2"))),
                command -> { throw new AssertionError("not expected"); });

        assertThatThrownBy(() -> adapter.advance("task-1", claim()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model progression consumed another selector identity");
    }

    @Test
    void controlWaitPerformsNoActionAndDeliveryUsesItsExactIdentity() {
        int[] calls = new int[2];
        var wait = new ProductChainNextRoleSelector.ControlWait(
                ProductChainNextRoleSelector.WaitKind
                        .NEXT_STEP_OR_READINESS_REQUIRED,
                "STEP_EVENT", "step-complete-1");
        adapter(waiting(wait), command -> {
            calls[0]++;
            throw new AssertionError("not expected");
        }, command -> {
            calls[1]++;
            throw new AssertionError("not expected");
        }).advance("task-1", claim());

        var delivery = new ProductChainNextRoleSelector.MechanicalDelivery(
                "delivery-1", 8);
        adapter(waiting(delivery), command -> {
            calls[0]++;
            throw new AssertionError("not expected");
        }, command -> {
            calls[1]++;
            assertThat(command.selection()).isEqualTo(delivery);
            return receipt(ProductChainTaskProgressionAdapter
                    .SelectedAction.mechanical(
                            "DELIVERY", delivery.deliveryId()));
        }).advance("task-1", claim());

        assertThat(calls).containsExactly(0, 1);
    }

    @Test
    void dispatchesDirectAnswerAndAcceptedProposalToTheirFormalOwners() {
        int[] calls = new int[2];
        var direct = directive(
                ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING,
                "ROUTE_DECISION", "route-1");
        var directAdapter = adapter(runtime(direct), command -> {
            calls[0]++;
            assertThat(command.directive()).isEqualTo(direct);
            return receipt(ProductChainTaskProgressionAdapter
                    .SelectedAction.model(direct));
        }, command -> {
            calls[1]++;
            throw new AssertionError("not expected");
        });
        directAdapter.advance("task-1", claim());

        var proposal = new ProductChainNextRoleSelector.MechanicalProposal(
                "proposal-1", ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_TOOL_ACTION,
                "proposal-accepted-1");
        var proposalAdapter = new ProductChainTaskProgressionAdapter(
                (taskId, observedAt) -> waiting(proposal),
                command -> { throw new AssertionError("not expected"); },
                command -> { throw new AssertionError("not expected"); },
                command -> {
                    calls[1]++;
                    assertThat(command.selection()).isEqualTo(proposal);
                    return receipt(ProductChainTaskProgressionAdapter
                            .SelectedAction.mechanical(
                                    "MODEL_PROPOSAL", "proposal-1"));
                }, Clock.fixed(NOW, ZoneOffset.UTC));
        proposalAdapter.advance("task-1", claim());
        assertThat(calls).containsExactly(1, 1);
    }

    @Test
    void dispatchesPendingItemValidationToTheModelOwner() {
        var validation = directive(
                ChainRole.EXECUTOR,
                ChainWorkState.VALIDATING_PENDING_ITEM,
                "PENDING_ITEM", "gap-1");
        int[] calls = new int[2];
        var adapter = adapter(runtime(validation), command -> {
            calls[0]++;
            assertThat(command.directive()).isEqualTo(validation);
            return receipt(ProductChainTaskProgressionAdapter
                    .SelectedAction.model(validation));
        }, command -> {
            calls[1]++;
            throw new AssertionError("not expected");
        });

        adapter.advance("task-1", claim());
        assertThat(calls).containsExactly(1, 0);
    }

    @Test
    void dispatchesTypedFinalizationFailureWaitToReflector() {
        var failure = directive(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "FINALIZATION_CHECK", "finalization-check-1");
        var outcome = new ChainRecoveryRuntime.RecoveryOutcome(
                ChainRecoveryRuntime.RecoveryDisposition
                        .WAITING_FORMAL_SUCCESSOR,
                snapshot(), List.of(),
                new ChainRecoveryRuntime.RecoveryResult(List.of(), false),
                failure);
        var recovered = new ProductChainRecoveryCoordinator.RuntimeOutcome(
                outcome, Optional.empty(), Optional.empty());
        List<ProductChainTaskProgressionAdapter.ModelCommand> calls =
                new ArrayList<>();
        var adapter = adapter(recovered, command -> {
            calls.add(command);
            return receipt(ProductChainTaskProgressionAdapter
                    .SelectedAction.model(command.directive()));
        }, command -> { throw new AssertionError("not expected"); });

        adapter.advance("task-1", claim());

        assertThat(calls).singleElement().satisfies(command ->
                assertThat(command.directive()).isEqualTo(failure));
    }

    @Test
    void doesNotDispatchAfterCoordinatorCompletedOneMechanicalSelection() {
        var selected = new ProductChainNextRoleSelector
                .MechanicalFinalization("readiness-1", 8);
        ChainPersistenceRecords.FinalizationCheckRecord check =
                mock(ChainPersistenceRecords.FinalizationCheckRecord.class);
        when(check.readinessId()).thenReturn("readiness-1");
        ChainFinalizationRuntime.Result finalized =
                new ChainFinalizationRuntime.CheckFailed(check);
        var recovered = new ProductChainRecoveryCoordinator.RuntimeOutcome(
                outcome(directive(
                        ChainRole.ANSWER, ChainWorkState.DELIVERING,
                        "TASK_OUTCOME", "outcome-1")),
                Optional.of(finalized), Optional.of(selected));
        int[] calls = new int[2];
        var adapter = adapter(recovered, command -> {
            calls[0]++;
            throw new AssertionError("not expected");
        }, command -> {
            calls[1]++;
            throw new AssertionError("not expected");
        });

        adapter.advance("task-1", claim());

        assertThat(calls).containsExactly(0, 0);
    }

    private static ProductChainTaskProgressionAdapter adapter(
            ProductChainRecoveryCoordinator.RecoveryResult result,
            ProductChainTaskProgressionAdapter.ModelProgression models,
            ProductChainTaskProgressionAdapter.MechanicalProgression mechanical) {
        return new ProductChainTaskProgressionAdapter(
                (taskId, observedAt) -> {
                    assertThat(taskId).isEqualTo("task-1");
                    assertThat(observedAt).isEqualTo(NOW);
                    return result;
                }, models, mechanical,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProductChainRecoveryCoordinator.RuntimeOutcome runtime(
            ChainRecoveryRuntime.NextDirective directive) {
        return new ProductChainRecoveryCoordinator.RuntimeOutcome(
                outcome(directive), Optional.empty(), Optional.empty());
    }

    private static ChainRecoveryRuntime.RecoveryOutcome outcome(
            ChainRecoveryRuntime.NextDirective directive) {
        return new ChainRecoveryRuntime.RecoveryOutcome(
                ChainRecoveryRuntime.RecoveryDisposition.NEXT_ROLE_SELECTED,
                snapshot(), List.of(),
                new ChainRecoveryRuntime.RecoveryResult(List.of(), false),
                directive);
    }

    private static ProductChainRecoveryCoordinator.Waiting waiting(
            ProductChainNextRoleSelector.Selection selection) {
        return new ProductChainRecoveryCoordinator.Waiting(
                snapshot(), selection, Optional.empty(), Optional.empty());
    }

    private static ChainRecoveryRuntime.NextDirective directive(
            ChainRole role, ChainWorkState state,
            String authorityType, String authorityRef) {
        return new ChainRecoveryRuntime.NextDirective(
                role, state, authorityType, authorityRef);
    }

    private static ProductChainTaskProgressionAdapter.ActionReceipt receipt(
            ProductChainTaskProgressionAdapter.SelectedAction action) {
        return new ProductChainTaskProgressionAdapter.ActionReceipt(action);
    }

    private static ProductChainProgressionClaim claim() {
        return new ProductChainProgressionClaim(
                "task-1", "driver-1", "token-1", 1, 7,
                NOW.minusSeconds(1), NOW.plusSeconds(30));
    }

    private static ChainRecoveryRuntime.RecoverySnapshot snapshot() {
        String boundary = "authority-event-sequence=7;transaction=test";
        List<ChainRecoveryRuntime.FactCut> cuts = new ArrayList<>();
        for (ChainRecoveryRuntime.RecoveryFactKind kind
                : ChainRecoveryRuntime.RecoveryFactKind.values()) {
            cuts.add(new ChainRecoveryRuntime.FactCut(
                    kind, "v1", boundary, List.of()));
        }
        return new ChainRecoveryRuntime.RecoverySnapshot(
                "task-1", cuts, List.of(),
                new ChainRecoveryRuntime.FrozenRoleProjection() {
                    @Override public String taskId() { return "task-1"; }
                    @Override public long authorityCut() { return 7; }
                    @Override public String readBoundary() { return boundary; }
                });
    }
}
