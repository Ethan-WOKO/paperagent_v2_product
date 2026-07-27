package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class ProductExecutionStartRecoveryTransactions {
    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductPlanBootstrapCodec bootstrapCodec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductExecutionStartCodec startCodec;

    ProductExecutionStartRecoveryTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec) {
        this.bootstraps = bootstraps;
        this.bootstrapCodec = bootstrapCodec;
        this.starts = starts;
        this.startCodec = startCodec;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public PersistenceResult<ExecutionStartRecoverySnapshot> inspect(
            PlanId planId) {
        Optional<ProductPlanBootstrapEntity> bootstrapRow =
                bootstraps.lockByPlanIdForInspection(planId.value());
        if (bootstrapRow.isEmpty()) {
            return starts.existsById(planId.value())
                    ? partial()
                    : PersistenceResult.rejected(
                            PersistenceErrorCode.NOT_FOUND, "planId");
        }

        PersistedPlanBootstrap bootstrap;
        try {
            ProductPlanBootstrapEntity row = bootstrapRow.get();
            bootstrap = bootstrapCodec.decode(
                    row.payloadFormatVersion(),
                    row.payloadSha256(),
                    row.payloadJson());
            if (!canonicalBootstrap(planId, row, bootstrap)) {
                return partial();
            }
        } catch (RuntimeException exception) {
            return partial();
        }

        Optional<ProductExecutionStartEntity> startRow =
                starts.findById(planId.value());
        if (startRow.isEmpty()) {
            return PersistenceResult.found(
                    new PersistedExecutionStartReady(
                            bootstrap, bootstrap.plan()));
        }

        try {
            ProductExecutionStartEntity row = startRow.get();
            ExecutionStartRequest request = startCodec.decodeRequest(
                    row.requestFormatVersion(),
                    row.requestSha256(),
                    row.requestJson());
            PersistedExecutionStart result = startCodec.decodeResult(
                    row.resultFormatVersion(),
                    row.resultSha256(),
                    row.resultJson());
            if (!canonicalStart(planId, bootstrap, row, request, result)) {
                return partial();
            }
            return PersistenceResult.found(
                    new PersistedExecutionStartCommitted(
                            bootstrap, bootstrap.plan(), result));
        } catch (RuntimeException exception) {
            return partial();
        }
    }

    private static boolean canonicalBootstrap(
            PlanId requested,
            ProductPlanBootstrapEntity row,
            PersistedPlanBootstrap bootstrap) {
        TaskFrame task = bootstrap.taskFrame();
        Plan plan = bootstrap.plan();
        VersionedCheckpoint source = bootstrap.initialCheckpoint();
        if (!row.planId().equals(requested.value())
                || !row.planId().equals(plan.id().value())
                || !row.taskFrameId().equals(task.id().value())
                || !task.id().equals(plan.taskFrameId())
                || source.version() != 1) {
            return false;
        }
        Checkpoint checkpoint = source.checkpoint();
        PlanRevision revision = plan.latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.taskFrameId().equals(task.id())
                && checkpoint.planId().equals(plan.id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(steps)
                && checkpoint.stepStates().values().stream().allMatch(
                        state -> state == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty();
    }

    private static boolean canonicalStart(
            PlanId requested,
            PersistedPlanBootstrap bootstrap,
            ProductExecutionStartEntity row,
            ExecutionStartRequest request,
            PersistedExecutionStart result) {
        Checkpoint started = result.startedCheckpoint().checkpoint();
        return row.committedAt() != null
                && row.planId().equals(requested.value())
                && row.planId().equals(request.planId().value())
                && row.planId().equals(result.planId().value())
                && row.startEventId().equals(request.startEvent().id().value())
                && row.startEventId().equals(result.startEvent().id().value())
                && row.leaseOwnerId().equals(result.leaseOwnerId())
                && row.fencingToken() == request.fencingToken()
                && row.fencingToken() == result.fencingToken()
                && request.startEvent().equals(result.startEvent())
                && request.startedCheckpoint().equals(started)
                && request.startEvent().planId().equals(requested)
                && request.startEvent().taskFrameId()
                        .equals(bootstrap.taskFrame().id())
                && started.planId().equals(requested)
                && started.taskFrameId().equals(bootstrap.taskFrame().id());
    }

    private static PersistenceResult<ExecutionStartRecoverySnapshot> partial() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }
}
