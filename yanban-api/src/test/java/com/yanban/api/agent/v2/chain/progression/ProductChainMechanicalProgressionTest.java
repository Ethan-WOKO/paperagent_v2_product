package com.yanban.api.agent.v2.chain.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.api.ProductChainAnswerDeliveryProgression;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.delivery.ChainDeliveryRuntime;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductChainMechanicalProgressionTest {
    private static final Instant NOW = Instant.parse("2026-08-09T05:00:00Z");

    @Test
    void retriesTheExactDeliverySelectedByTheFrozenSnapshot() {
        Fixture fixture = new Fixture(7, 9);
        var attempted = mock(ChainDeliveryRuntime.Attempted.class);
        when(attempted.delivery()).thenReturn(fixture.delivery);
        when(fixture.answer.retryDelivery("task-1", "delivery-1", NOW))
                .thenReturn(attempted);

        var receipt = fixture.progression.advance(fixture.command());

        assertThat(receipt.consumedSelection()).isEqualTo(
                ProductChainTaskProgressionAdapter.SelectedAction.mechanical(
                        "DELIVERY", "delivery-1"));
        verify(fixture.answer).retryDelivery("task-1", "delivery-1", NOW);
    }

    @Test
    void rejectsASelectionWhoseAuthoritySequenceIsStale() {
        Fixture fixture = new Fixture(8, 9);

        assertThatThrownBy(() -> fixture.progression.advance(
                fixture.command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_MECHANICAL_DELIVERY_SELECTION_STALE");
    }

    @Test
    void rejectsAnAuthorityOutsideTheFrozenReadCut() {
        Fixture fixture = new Fixture(7, 6);

        assertThatThrownBy(() -> fixture.progression.advance(
                fixture.command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_MECHANICAL_DELIVERY_SELECTION_STALE");
    }

    @Test
    void permissionSelectionHasADedicatedMechanicalOwnerBoundary() {
        Fixture fixture = new Fixture(7, 9);

        assertThatThrownBy(() -> fixture.progression.advance(
                fixture.permissionCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_PERMISSION_DECISION_OWNER_MISSING");
    }

    private static final class Fixture {
        private final ProductChainFoundationRepositoryAdapter foundations =
                mock(ProductChainFoundationRepositoryAdapter.class);
        private final ProductChainFinalizationRepositoryAdapter finalization =
                mock(ProductChainFinalizationRepositoryAdapter.class);
        private final ProductChainAnswerDeliveryProgression answer =
                mock(ProductChainAnswerDeliveryProgression.class);
        private final ChainPersistenceRecords.DeliveryRecord delivery =
                mock(ChainPersistenceRecords.DeliveryRecord.class);
        private final ProductChainMechanicalProgression progression;
        private final long selectedSequence;
        private final long snapshotCut;

        private Fixture(long selectedSequence, long snapshotCut) {
            this.selectedSequence = selectedSequence;
            this.snapshotCut = snapshotCut;
            when(delivery.taskId()).thenReturn("task-1");
            when(delivery.deliveryId()).thenReturn("delivery-1");
            when(delivery.eventId()).thenReturn("delivery-event-1");
            when(finalization.findDeliveries("task-1"))
                    .thenReturn(List.of(delivery));
            when(foundations.highestAuthorityEventSequence("task-1"))
                    .thenReturn(9L);
            when(foundations.findAuthorityEvents("task-1", 9L))
                    .thenReturn(List.of(
                            new ChainPersistenceRecords.AuthorityEventRecord(
                                    "delivery-event-1", "task-1", 7,
                                    "DELIVERY", null, "0".repeat(64), NOW)));
            progression = new ProductChainMechanicalProgression(
                    foundations, finalization, answer,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private ProductChainTaskProgressionAdapter.MechanicalCommand command() {
            return command(new ProductChainNextRoleSelector
                    .MechanicalDelivery("delivery-1", selectedSequence));
        }

        private ProductChainTaskProgressionAdapter.MechanicalCommand
                permissionCommand() {
            return command(new ProductChainNextRoleSelector
                    .MechanicalPermission("permission-1", selectedSequence));
        }

        private ProductChainTaskProgressionAdapter.MechanicalCommand command(
                ProductChainNextRoleSelector.Selection selection) {
            var snapshot = mock(ChainRecoveryRuntime.RecoverySnapshot.class);
            var projection = mock(
                    ChainRecoveryRuntime.FrozenRoleProjection.class);
            when(snapshot.taskId()).thenReturn("task-1");
            when(projection.authorityCut()).thenReturn(snapshotCut);
            when(snapshot.roleProjection()).thenReturn(projection);
            var claim = mock(ProductChainProgressionClaim.class);
            when(claim.taskId()).thenReturn("task-1");
            return new ProductChainTaskProgressionAdapter.MechanicalCommand(
                    "task-1", snapshot, claim, selection);
        }
    }
}
