package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;

import java.util.ArrayList;

final class InMemoryPlanRepository implements PlanRepository {
    private final InMemoryState state;

    InMemoryPlanRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<Plan> create(Plan plan) {
        if (PersistenceChecks.missing(plan)) {
            return PersistenceChecks.invalid("plan");
        }
        synchronized (state.monitor) {
            Plan existing = state.plans.get(plan.id());
            if (existing != null) {
                return existing.equals(plan)
                        ? PersistenceResult.replayed(existing)
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY, "plan.id");
            }
            if (!state.taskFrames.containsKey(plan.taskFrameId())) {
                return PersistenceChecks.notFound("plan.taskFrameId");
            }
            state.plans.put(plan.id(), plan);
            return PersistenceResult.applied(plan);
        }
    }

    @Override
    public PersistenceResult<Plan> find(PlanId planId) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            Plan plan = state.plans.get(planId);
            return plan == null
                    ? PersistenceChecks.notFound("planId")
                    : PersistenceResult.found(plan);
        }
    }

    @Override
    public PersistenceResult<Plan> appendRevision(
            PlanId planId,
            long expectedCurrentRevisionNumber,
            PlanRevision revision) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.missing(revision)) {
            return PersistenceChecks.invalid("revision");
        }
        if (expectedCurrentRevisionNumber < 1) {
            return PersistenceChecks.invalid("expectedCurrentRevisionNumber");
        }
        synchronized (state.monitor) {
            Plan current = state.plans.get(planId);
            PlanRevision existing = current == null
                    ? null
                    : current.revisions().stream()
                            .filter(candidate ->
                                    candidate.id().equals(revision.id()))
                            .findFirst()
                            .orElse(null);
            if (existing != null) {
                return existing.equals(revision)
                        ? PersistenceResult.replayed(current)
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY, "revision.id");
            }
            if (state.executionStarts.containsKey(planId)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                        "planId");
            }
            if (current == null) {
                return PersistenceChecks.notFound("planId");
            }
            if (current.latestRevision().number() != expectedCurrentRevisionNumber) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION, "expectedCurrentRevisionNumber");
            }
            if (!revision.taskFrameId().equals(current.taskFrameId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.TASK_FRAME_MISMATCH, "revision.taskFrameId");
            }
            ArrayList<PlanRevision> revisions = new ArrayList<>(current.revisions());
            revisions.add(revision);
            try {
                Plan updated = new Plan(current.id(), current.taskFrameId(), revisions);
                state.plans.put(planId, updated);
                return PersistenceResult.applied(updated);
            } catch (ContractViolationException invalidRevision) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.PLAN_VALIDATION_FAILED, "revision");
            }
        }
    }
}
