package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.persistence.PersistedPlanReplan;
import io.paperagent.v2.persistence.PlanReplanRequest;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/** Narrow stable marker projection consumed by ordinary Plan recovery. */
@Repository
public class ProductPlanReplanMarkerReader {
    private final ProductPlanReplanJpaRepository rows;
    private final ProductPlanReplanCodec codec;
    private final EntityManager entityManager;

    public ProductPlanReplanMarkerReader(
            ProductPlanReplanJpaRepository rows,
            ProductPlanReplanCodec codec,
            EntityManager entityManager) {
        this.rows = rows;
        this.codec = codec;
        this.entityManager = entityManager;
    }

    public List<Marker> findAllByPlanId(String planId) {
        List<ProductPlanReplanEntity> found =
                rows.findAllByPlanIdOrderBySourceEventSequenceAsc(planId);
        if (found.isEmpty()) {
            return List.of();
        }
        String taskId = boundTaskId(planId);
        return found.stream().map(row -> decode(row, taskId)).toList();
    }

    public String authoritySha256(Marker marker) {
        return codec.authoritySha256(
                Objects.requireNonNull(marker, "marker").result());
    }

    Marker decode(ProductPlanReplanEntity row, String expectedTaskId) {
        PlanReplanRequest request = codec.decodeRequest(
                row.requestFormatVersion(), row.requestSha256(),
                row.requestJson());
        PersistedPlanReplan result = codec.decodeResult(
                row.resultFormatVersion(), row.resultSha256(),
                row.resultJson());
        if (!expectedTaskId.equals(row.taskId())
                || !canonicalColumns(row, request, result)) {
            throw new ProductChainPersistenceException(
                    "PLAN_REPLAN_PARTIAL_STATE");
        }
        return new Marker(row.taskId(), row.sourceEventSequence(),
                row.resultEventSequence(), request, result,
                row.committedAt());
    }

    @SuppressWarnings("unchecked")
    private String boundTaskId(String planId) {
        List<String> taskIds = entityManager.createNativeQuery("""
                SELECT binding.task_id
                  FROM agent_v2_chain_plan_bindings binding
                  JOIN agent_v2_chain_tasks task
                    ON task.task_id = binding.task_id
                 WHERE binding.plan_id = :planId
                """, String.class).setParameter("planId", planId)
                .getResultList();
        if (taskIds.size() != 1) {
            throw new ProductChainPersistenceException(
                    "PLAN_REPLAN_PARTIAL_STATE");
        }
        return taskIds.get(0);
    }

    private static boolean canonicalColumns(
            ProductPlanReplanEntity row, PlanReplanRequest request,
            PersistedPlanReplan result) {
        return row.committedAt() != null
                && row.planId().equals(request.planId().value())
                && row.planId().equals(result.planId().value())
                && row.replanEventId().equals(
                        request.replanEvent().id().value())
                && row.replanEventId().equals(
                        result.replanEvent().id().value())
                && row.sourceEventSequence()
                        == request.expectedEventHeadSequence()
                && row.sourceRevisionId().equals(
                        request.expectedRevisionId().value())
                && row.sourceRevisionNumber()
                        == request.expectedRevisionNumber()
                && row.resultRevisionId().equals(
                        result.replannedRevision().id().value())
                && row.resultRevisionNumber()
                        == result.replannedRevision().number()
                && row.sourceCheckpointVersion()
                        == request.expectedCheckpointVersion()
                && row.resultCheckpointVersion()
                        == result.replannedCheckpoint().version()
                && row.resultEventSequence()
                        == result.replanEvent().sequence()
                && row.leaseOwner().equals(result.leaseOwnerId())
                && row.fenceToken() == request.fencingToken()
                && row.fenceToken() == result.fencingToken()
                && request.replanEvent().equals(result.replanEvent())
                && request.replannedRevision().equals(
                        result.replannedRevision())
                && request.replannedCheckpoint().equals(
                        result.replannedCheckpoint().checkpoint());
    }

    public record Marker(
            String taskId,
            long sourceEventSequence,
            long resultEventSequence,
            PlanReplanRequest request,
            PersistedPlanReplan result,
            java.time.Instant committedAt) {
    }
}
