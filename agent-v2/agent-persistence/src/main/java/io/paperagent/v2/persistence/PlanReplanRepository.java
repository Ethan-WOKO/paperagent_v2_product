package io.paperagent.v2.persistence;

public interface PlanReplanRepository {
    PersistenceResult<PersistedPlanReplan> replan(PlanReplanRequest request);
}
