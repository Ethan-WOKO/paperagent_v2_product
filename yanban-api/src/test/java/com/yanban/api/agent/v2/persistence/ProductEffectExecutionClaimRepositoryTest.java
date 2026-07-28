package com.yanban.api.agent.v2.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductEffectExecutionClaimRepositoryTest {
    @Test
    void precedingStepCompletionDoesNotBlockCurrentActiveStepReplay() {
        var bootstraps = mock(ProductPlanBootstrapJpaRepository.class);
        var activations = mock(ProductStepActivationJpaRepository.class);
        var activationCodec = mock(ProductStepActivationCodec.class);
        var completions = mock(ProductStepCompletionJpaRepository.class);
        var leases = mock(ProductLeaseJpaRepository.class);
        var claims = mock(ProductEffectExecutionClaimJpaRepository.class);
        var results = mock(ProductEffectOutcomeResultJpaRepository.class);
        var markers = mock(ProductEffectOutcomeMarkerReader.class);
        var receipts = mock(ProductReceiptJpaRepository.class);
        var receiptCodec = mock(ProductReceiptCodec.class);
        var outcomeCodec = mock(ProductEffectOutcomeCodec.class);
        var entityManager = mock(EntityManager.class);
        var transactions = new ProductEffectExecutionClaimTransactions(
                bootstraps, activations, activationCodec, completions, leases,
                claims, results, markers, receipts, receiptCodec,
                outcomeCodec, entityManager);

        PlanId planId = new PlanId("plan-a");
        PlanStepId stepId = new PlanStepId("step-b");
        ToolCallId toolCallId = new ToolCallId("tool-b");
        EventId eventId = new EventId("activation-b");
        EffectIntent effect = mock(EffectIntent.class);
        when(effect.planId()).thenReturn(planId);
        when(effect.stepId()).thenReturn(stepId);
        when(effect.toolCallId()).thenReturn(toolCallId);
        PersistedEffectIntent intent = new PersistedEffectIntent(
                effect, "owner", 2, eventId);
        PersistedStepActivation activation =
                mock(PersistedStepActivation.class);
        EventEnvelope event = mock(EventEnvelope.class);
        when(activation.stepId()).thenReturn(stepId);
        when(activation.activationEvent()).thenReturn(event);
        when(event.id()).thenReturn(eventId);
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.stepStates()).thenReturn(Map.of(
                new PlanStepId("step-a"), StepExecutionState.SUCCEEDED,
                stepId, StepExecutionState.ACTIVE));
        VersionedCheckpoint versioned = mock(VersionedCheckpoint.class);
        when(versioned.checkpoint()).thenReturn(checkpoint);
        PersistedStepRecoveryActive recovery =
                mock(PersistedStepRecoveryActive.class);
        when(recovery.planId()).thenReturn(planId);
        when(recovery.activation()).thenReturn(activation);
        when(recovery.checkpoint()).thenReturn(versioned);
        LeaseRecord lease = new LeaseRecord(
                planId, "owner", "token", 2,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:10:00Z"));
        ProductEffectExecutionClaimRequest request =
                new ProductEffectExecutionClaimRequest(
                        recovery, lease, intent, "token", 2,
                        Instant.parse("2026-07-28T00:01:00Z"),
                        () -> {
                            throw new AssertionError("replay must not execute");
                        });

        when(bootstraps.lockByPlanId("plan-a")).thenReturn(
                Optional.of(mock(ProductPlanBootstrapEntity.class)));
        when(markers.intent("tool-b")).thenReturn(intent);
        ProductStepActivationEntity activationRow =
                mock(ProductStepActivationEntity.class);
        when(activations.findById("activation-b"))
                .thenReturn(Optional.of(activationRow));
        when(activationCodec.decodeResult(any(Integer.class), any(), any()))
                .thenReturn(activation);
        when(completions.findAllByPlanId("plan-a"))
                .thenReturn(List.of(mock(ProductStepCompletionEntity.class)));
        when(completions.findByPlanIdAndStepIdAndActivationEventId(
                "plan-a", "step-b", "activation-b"))
                .thenReturn(Optional.empty());
        ProductLeaseEntity leaseRow = mock(ProductLeaseEntity.class);
        when(leaseRow.leaseToken()).thenReturn("token");
        when(leaseRow.ownerId()).thenReturn("owner");
        when(leaseRow.fencingToken()).thenReturn(2L);
        when(leases.findFirstByPlanIdOrderByFencingTokenDesc("plan-a"))
                .thenReturn(Optional.of(leaseRow));
        ProductEffectOutcomeResultEntity resultRow =
                mock(ProductEffectOutcomeResultEntity.class);
        when(results.findById("tool-b")).thenReturn(Optional.of(resultRow));
        ProductEffectExecutionClaimEntity claim =
                new ProductEffectExecutionClaimEntity(
                        "tool-b", "plan-a", "step-b", "activation-b",
                        Instant.parse("2026-07-28T00:00:30Z"));
        when(claims.findById("tool-b")).thenReturn(Optional.of(claim));
        PersistedEffectResult persisted = mock(PersistedEffectResult.class);
        when(markers.result(resultRow)).thenReturn(
                new ProductEffectOutcomeMarkerReader.ResultMarker(
                        mock(io.paperagent.v2.persistence
                                .EffectResultRequest.class),
                        persisted));

        assertEquals(persisted, transactions.execute(request).result());
        verify(completions, never()).findAllByPlanId("plan-a");
    }

    @Test
    void migrationRejectsDuplicateAndOrphanClaims() throws Exception {
        String url = schema("claim_repository");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(intent("tool-a"));
            statement.execute(claim("tool-a"));
            assertEquals(1, count(statement));
            assertThrows(SQLException.class,
                    () -> statement.execute(claim("tool-a")));
            assertThrows(SQLException.class,
                    () -> statement.execute(claim("missing")));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM agent_v2_effect_intents "
                            + "WHERE tool_call_id='tool-a'"));
        }
    }

    static String schema(String name) throws Exception {
        String url = "jdbc:h2:mem:" + name
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_v2_effect_intents (
                      tool_call_id VARCHAR(128) NOT NULL,
                      plan_id VARCHAR(128) NOT NULL,
                      step_id VARCHAR(128) NOT NULL,
                      activation_event_id VARCHAR(128) NOT NULL,
                      PRIMARY KEY (tool_call_id),
                      CONSTRAINT uk_agent_v2_effect_intent_binding UNIQUE
                        (tool_call_id, plan_id, step_id, activation_event_id))
                    """);
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("52")
                .target("53").load().migrate();
        return url;
    }

    static String intent(String toolCall) {
        return "INSERT INTO agent_v2_effect_intents VALUES ('"
                + toolCall + "','plan-a','step-a','activation-a')";
    }

    static String claim(String toolCall) {
        return "INSERT INTO agent_v2_effect_execution_claims VALUES ('"
                + toolCall
                + "','plan-a','step-a','activation-a',"
                + "TIMESTAMP '2026-07-28 00:00:00')";
    }

    static long count(Statement statement) throws Exception {
        try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM agent_v2_effect_execution_claims")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
