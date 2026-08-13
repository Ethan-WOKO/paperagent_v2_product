package com.yanban.api.agent.v2.chain.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.persistence.ProductStepRecoveryTransactions;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.PersistedPlanReplan;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanReplanRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPlanReplanRepositoryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    void ordinaryReplanWithoutValidationBindingsIsStillWrittenAsFormatTwo() {
        ProductPlanReplanCodec codec = new ProductPlanReplanCodec(
                new ObjectMapper());

        var encoded = codec.encodeRequest(fixture().request());

        assertEquals(2, encoded.formatVersion());
        org.junit.jupiter.api.Assertions.assertTrue(encoded.json().contains(
                "\"validationRequirementIds\":[]"));
    }

    @Test
    void legacyFormatOneRemainsReadableButIsUpgradedOnWrite()
            throws Exception {
        ProductPlanReplanCodec codec = new ProductPlanReplanCodec(
                new ObjectMapper());
        var source = codec.encodeRequest(fixture().request());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode legacy = (ObjectNode) mapper.readTree(source.json());
        legacy.put("format", 1);
        legacy.withObject("replannedRevision").withArray("steps")
                .forEach(step -> ((ObjectNode) step)
                        .remove("validationRequirementIds"));
        String json = mapper.writeValueAsString(legacy);
        String hash = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        json.getBytes(StandardCharsets.UTF_8)));

        var decoded = codec.decodeRequest(1, hash, json);

        assertEquals(List.of(), decoded.replannedRevision().steps().get(0)
                .validationRequirementIds());
        assertEquals(2, codec.encodeRequest(decoded).formatVersion());
    }

    @Test
    void codecRoundTripsStableValidationBindingsInFormatTwo() {
        ProductPlanReplanCodec codec = new ProductPlanReplanCodec(
                new ObjectMapper());
        PlanReplanRequest base = fixture().request();
        PlanRevision oldRevision = base.replannedRevision();
        PlanStep oldStep = oldRevision.steps().get(0);
        PlanStep boundStep = new PlanStep(
                oldStep.id(), oldStep.intent(), oldStep.expectedOutcome(),
                oldStep.dependencies(), oldStep.completionCriteria(),
                oldStep.executionHints(), oldStep.constraints(), false, null,
                List.of("validate-action"));
        PlanRevision revision = new PlanRevision(
                oldRevision.id(), oldRevision.taskFrameId(), oldRevision.number(),
                oldRevision.parentRevisionId(), oldRevision.reason(),
                oldRevision.createdAt(), List.of(boundStep),
                oldRevision.completedFacts());
        PlanReplanRequest request = new PlanReplanRequest(
                base.planId(), base.leaseToken(), base.fencingToken(),
                base.expectedRevisionId(), base.expectedRevisionNumber(),
                base.expectedCheckpointVersion(),
                base.expectedEventHeadSequence(), base.replanEvent(), revision,
                base.replannedCheckpoint());

        var encoded = codec.encodeRequest(request);
        var decoded = codec.decodeRequest(
                encoded.formatVersion(), encoded.sha256(), encoded.json());

        assertEquals(2, encoded.formatVersion());
        assertEquals(List.of("validate-action"), decoded.replannedRevision()
                .steps().get(0).validationRequirementIds());
    }

    @Test
    void appliesThenPermanentlyReplaysTheCanonicalMarker() {
        ProductPlanReplanJpaRepository rows =
                mock(ProductPlanReplanJpaRepository.class);
        ProductStepRecoveryTransactions recovery =
                mock(ProductStepRecoveryTransactions.class);
        EntityManager entityManager = mock(EntityManager.class);
        ProductPlanReplanCodec codec = new ProductPlanReplanCodec(
                new ObjectMapper());
        ProductPlanReplanMarkerReader markers =
                new ProductPlanReplanMarkerReader(
                        rows, codec, entityManager);
        AtomicReference<ProductPlanReplanEntity> stored =
                new AtomicReference<>();
        when(rows.findByReplanEventId("replan-event"))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(entityManager).persist(any(ProductPlanReplanEntity.class));
        queries(entityManager);

        Fixture fixture = fixture();
        when(recovery.inspectLocked(fixture.request().planId()))
                .thenReturn(PersistenceResult.found(fixture.ready()));
        var transactions = new ProductPlanReplanTransactions(
                rows, markers, codec, recovery, () -> NOW, entityManager);
        var repository = new ProductPlanReplanRepositoryAdapter(transactions);

        var applied = repository.replan(fixture.request());
        var replayed = repository.replan(fixture.request());

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(applied.value(), replayed.value());
        assertEquals("task-1", stored.get().taskId());
        assertEquals(2, stored.get().resultEventSequence());
    }

    @Test
    void replayRejectsAMarkerOwnedByAnotherTask() {
        ProductPlanReplanJpaRepository rows =
                mock(ProductPlanReplanJpaRepository.class);
        ProductStepRecoveryTransactions recovery =
                mock(ProductStepRecoveryTransactions.class);
        EntityManager entityManager = mock(EntityManager.class);
        ProductPlanReplanCodec codec = new ProductPlanReplanCodec(
                new ObjectMapper());
        ProductPlanReplanMarkerReader markers =
                new ProductPlanReplanMarkerReader(
                        rows, codec, entityManager);
        Fixture fixture = fixture();
        ProductPlanReplanEntity crossed = row(
                "other-task", fixture, codec,
                fixture.request().expectedRevisionNumber());
        when(rows.findByReplanEventId("replan-event"))
                .thenReturn(Optional.of(crossed));
        when(recovery.inspectLocked(fixture.request().planId()))
                .thenReturn(PersistenceResult.found(fixture.ready()));
        Query taskLock = queries(entityManager);
        var transactions = new ProductPlanReplanTransactions(
                rows, markers, codec, recovery, () -> NOW, entityManager);

        var replay = transactions.replan(fixture.request());

        var ordered = inOrder(taskLock, recovery);
        ordered.verify(taskLock).getResultList();
        ordered.verify(recovery).inspectLocked(fixture.request().planId());
        assertEquals(PersistenceOutcome.REJECTED, replay.outcome());
        assertEquals(PersistenceErrorCode.PLAN_REPLAN_PARTIAL_STATE,
                replay.failure().orElseThrow().code());
    }

    @Test
    void recoveryReaderRejectsAMarkerOutsideTheUniquePlanBinding() {
        ProductPlanReplanJpaRepository rows =
                mock(ProductPlanReplanJpaRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ProductPlanReplanCodec codec = new ProductPlanReplanCodec(
                new ObjectMapper());
        ProductPlanReplanMarkerReader markers =
                new ProductPlanReplanMarkerReader(
                        rows, codec, entityManager);
        Fixture fixture = fixture();
        when(rows.findAllByPlanIdOrderBySourceEventSequenceAsc("plan-1"))
                .thenReturn(List.of(row(
                        "other-task", fixture, codec,
                        fixture.request().expectedRevisionNumber())));
        queries(entityManager);

        ProductChainPersistenceException corrupt = assertThrows(
                ProductChainPersistenceException.class,
                () -> markers.findAllByPlanId("plan-1"));

        assertEquals("PLAN_REPLAN_PARTIAL_STATE", corrupt.code());
    }

    @Test
    void classifyRejectsACorruptSourceWinnerAsPartial() {
        ProductPlanReplanJpaRepository rows =
                mock(ProductPlanReplanJpaRepository.class);
        ProductStepRecoveryTransactions recovery =
                mock(ProductStepRecoveryTransactions.class);
        EntityManager entityManager = mock(EntityManager.class);
        ProductPlanReplanCodec codec = new ProductPlanReplanCodec(
                new ObjectMapper());
        ProductPlanReplanMarkerReader markers =
                new ProductPlanReplanMarkerReader(
                        rows, codec, entityManager);
        Fixture fixture = fixture();
        when(rows.findByReplanEventId("replan-event"))
                .thenReturn(Optional.empty());
        when(rows.findAllByPlanIdOrderBySourceEventSequenceAsc("plan-1"))
                .thenReturn(List.of(row(
                        "task-1", fixture, codec,
                        fixture.request().expectedRevisionNumber() + 1)));
        when(recovery.inspectLocked(fixture.request().planId()))
                .thenReturn(PersistenceResult.found(fixture.ready()));
        queries(entityManager);
        var transactions = new ProductPlanReplanTransactions(
                rows, markers, codec, recovery, () -> NOW, entityManager);

        var classified = transactions.classify(fixture.request());

        assertEquals(PersistenceOutcome.REJECTED, classified.outcome());
        assertEquals(PersistenceErrorCode.PLAN_REPLAN_PARTIAL_STATE,
                classified.failure().orElseThrow().code());
    }

    @Test
    void rejectsNullWithoutEnteringTheTransaction() {
        var transactions = mock(ProductPlanReplanTransactions.class);
        var repository = new ProductPlanReplanRepositoryAdapter(transactions);

        assertEquals(PersistenceOutcome.REJECTED,
                repository.replan(null).outcome());
    }

    private static Query queries(EntityManager entityManager) {
        Query binding = mock(Query.class);
        when(entityManager.createNativeQuery(anyString(), eq(String.class)))
                .thenReturn(binding);
        when(binding.setParameter(anyString(), any())).thenReturn(binding);
        when(binding.getResultList()).thenReturn(List.of("task-1"));

        Query task = mock(Query.class);
        Query lease = mock(Query.class);
        Query occupied = mock(Query.class);
        when(task.setParameter(anyString(), any())).thenReturn(task);
        when(lease.setParameter(anyString(), any())).thenReturn(lease);
        when(occupied.setParameter(anyString(), any())).thenReturn(occupied);
        when(task.getResultList()).thenReturn(List.of("task-1"));
        when(lease.getResultList()).thenReturn(Collections.singletonList(new Object[]{
                "owner-1", "lease-1", 3L,
                Timestamp.from(NOW.plusSeconds(60)), null
        }));
        when(occupied.getSingleResult()).thenReturn(0L);
        when(entityManager.createNativeQuery(anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("agent_v2_chain_tasks")) {
                        return task;
                    }
                    if (sql.contains("agent_v2_plan_leases")) {
                        return lease;
                    }
                    return occupied;
                });
        return task;
    }

    private static Fixture fixture() {
        TaskFrameId taskFrameId = new TaskFrameId("frame-1");
        PlanId planId = new PlanId("plan-1");
        PlanStepId stepId = new PlanStepId("step-1");
        ExecutionProfile profile = new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT), NetworkPolicy.DENY_ALL,
                List.of(), new ResourceLimits(
                Duration.ofMinutes(2), Duration.ofMinutes(1),
                128_000_000, 1_000_000, 1), Set.of());
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId, "objective", List.of("object"),
                List.of("deliverable"), List.of("constraint"),
                Optional.empty(), profile, NOW.minusSeconds(10));
        PlanStep step = new PlanStep(
                stepId, "work", "done", Set.of(), List.of("done"),
                new BoundedExecutionHints(2, Duration.ofMinutes(1)));
        PlanRevision revision1 = new PlanRevision(
                new PlanRevisionId("revision-1"), taskFrameId, 1,
                Optional.empty(), "initial", NOW.minusSeconds(9),
                List.of(step), Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision1));
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        states.put(stepId, StepExecutionState.NOT_STARTED);
        Checkpoint head = new Checkpoint(
                taskFrameId, planId, revision1.id(), 1, 1,
                PlanExecutionState.ACTIVE, states, List.of(),
                NOW.minusSeconds(8));
        var ready = new PersistedStepRecoveryReady(
                taskFrame, plan, new VersionedCheckpoint(2, head), stepId,
                Optional.empty());
        PlanRevision revision2 = new PlanRevision(
                new PlanRevisionId("revision-2"), taskFrameId, 2,
                Optional.of(revision1.id()), "ordinary replan",
                NOW.minusSeconds(7), List.of(step), Map.of());
        Checkpoint target = new Checkpoint(
                taskFrameId, planId, revision2.id(), 2, 2,
                PlanExecutionState.ACTIVE, states, List.of(),
                NOW.minusSeconds(6));
        EventEnvelope event = new EventEnvelope(
                new EventId("replan-event"), taskFrameId, planId, 2,
                NOW.minusSeconds(6), new EventType("PLAN_REPLANNED"),
                Optional.empty(), "correlation-1",
                new InlineEventPayload(new ObjectValue(Map.of(
                        "reason", new TextValue("ordinary")))));
        PlanReplanRequest request = new PlanReplanRequest(
                planId, "lease-1", 3, revision1.id(), 1, 2, 1,
                event, revision2, target);
        return new Fixture(ready, request);
    }

    private static ProductPlanReplanEntity row(
            String taskId, Fixture fixture, ProductPlanReplanCodec codec,
            long sourceRevisionNumber) {
        PlanReplanRequest request = fixture.request();
        PersistedPlanReplan result = new PersistedPlanReplan(
                request.planId(), "owner-1", request.fencingToken(),
                request.replanEvent(), request.replannedRevision(),
                new VersionedCheckpoint(
                        request.expectedCheckpointVersion() + 1,
                        request.replannedCheckpoint()));
        return new ProductPlanReplanEntity(
                taskId, request.planId().value(),
                request.expectedEventHeadSequence(),
                request.expectedRevisionId().value(), sourceRevisionNumber,
                request.replannedRevision().id().value(),
                request.replannedRevision().number(),
                request.expectedCheckpointVersion(),
                request.expectedCheckpointVersion() + 1,
                request.replanEvent().id().value(),
                request.replanEvent().sequence(), "owner-1",
                request.fencingToken(), codec.encodeRequest(request),
                codec.encodeResult(result), NOW);
    }

    private record Fixture(
            PersistedStepRecoveryReady ready,
            PlanReplanRequest request) {
    }
}
