package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProductPlanBootstrapRepositoryAdapter implements PlanBootstrapRepository {
    private final ProductPlanBootstrapTransactions transactions;
    private final ProductPlanBootstrapCodec codec;

    public ProductPlanBootstrapRepositoryAdapter(
            ProductPlanBootstrapTransactions transactions,
            ProductPlanBootstrapCodec codec) {
        this.transactions = transactions;
        this.codec = codec;
    }

    @Override
    public PersistenceResult<PersistedPlanBootstrap> bootstrap(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) {
        PersistenceResult<PersistedPlanBootstrap> invalid =
                validateRequired(taskFrame, plan, checkpoint);
        if (invalid != null) {
            return invalid;
        }

        ProductPlanBootstrapEntity byPlan = transactions
                .findByPlanId(plan.id().value()).orElse(null);
        if (byPlan != null) {
            return classifyPlanReplay(byPlan, taskFrame, plan, checkpoint);
        }
        if (transactions.findByTaskFrameId(taskFrame.id().value()).isPresent()) {
            return partialState();
        }
        invalid = validateCanonical(taskFrame, plan, checkpoint);
        if (invalid != null) {
            return invalid;
        }

        PersistedPlanBootstrap bootstrap = new PersistedPlanBootstrap(
                taskFrame, plan, new VersionedCheckpoint(1, checkpoint));
        ProductPlanBootstrapCodec.EncodedPayload payload = codec.encode(bootstrap);
        ProductPlanBootstrapEntity entity = new ProductPlanBootstrapEntity(
                plan.id().value(),
                taskFrame.id().value(),
                payload.formatVersion(),
                payload.sha256(),
                payload.json(),
                Instant.now());
        try {
            ProductPlanBootstrapEntity inserted = transactions.insert(entity);
            return PersistenceResult.applied(decode(inserted));
        } catch (DataIntegrityViolationException | ConstraintViolationException insertRace) {
            // insert() is REQUIRES_NEW. Its transaction has fully rolled back before
            // this fresh REQUIRES_NEW read is entered through the Spring proxy.
            ProductPlanBootstrapEntity winner = transactions
                    .findByPlanId(plan.id().value()).orElse(null);
            if (winner != null) {
                return classifyPlanReplay(winner, taskFrame, plan, checkpoint);
            }
            ProductPlanBootstrapEntity taskFrameWinner = transactions
                    .findByTaskFrameId(taskFrame.id().value()).orElse(null);
            if (taskFrameWinner != null) {
                return taskFrameWinner.planId().equals(plan.id().value())
                        ? classifyPlanReplay(
                                taskFrameWinner, taskFrame, plan, checkpoint)
                        : partialState();
            }
            throw insertRace;
        }
    }

    private PersistenceResult<PersistedPlanBootstrap> classifyPlanReplay(
            ProductPlanBootstrapEntity entity,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) {
        PersistedPlanBootstrap stored = decode(entity);
        boolean exact = stored.taskFrame().equals(taskFrame)
                && stored.plan().equals(plan)
                && stored.initialCheckpoint().checkpoint().equals(checkpoint);
        return exact
                ? PersistenceResult.replayed(stored)
                : PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY, "plan.id");
    }

    private PersistedPlanBootstrap decode(ProductPlanBootstrapEntity entity) {
        PersistedPlanBootstrap stored = codec.decode(
                entity.payloadFormatVersion(),
                entity.payloadSha256(),
                entity.payloadJson());
        if (!stored.plan().id().value().equals(entity.planId())
                || !stored.taskFrame().id().value().equals(entity.taskFrameId())) {
            throw new IllegalStateException("Stored V2 Plan bootstrap identity is invalid");
        }
        return stored;
    }

    private static PersistenceResult<PersistedPlanBootstrap> validateRequired(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) {
        if (taskFrame == null) {
            return invalid("taskFrame");
        }
        if (plan == null) {
            return invalid("plan");
        }
        if (checkpoint == null) {
            return invalid("checkpoint");
        }
        return null;
    }

    private static PersistenceResult<PersistedPlanBootstrap> validateCanonical(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) {
        if (!plan.taskFrameId().equals(taskFrame.id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "plan.taskFrameId");
        }
        if (!CheckpointValidators.validate(checkpoint, taskFrame, plan, null).isEmpty()
                || !isInitialShape(checkpoint, plan)) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "checkpoint");
        }
        return null;
    }

    private static boolean isInitialShape(Checkpoint checkpoint, Plan plan) {
        Set<PlanStepId> stepIds = plan.latestRevision().steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(stepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value -> value == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty();
    }

    private static PersistenceResult<PersistedPlanBootstrap> invalid(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.INVALID_ARGUMENT, path);
    }

    private static PersistenceResult<PersistedPlanBootstrap> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE, "bootstrap");
    }
}
