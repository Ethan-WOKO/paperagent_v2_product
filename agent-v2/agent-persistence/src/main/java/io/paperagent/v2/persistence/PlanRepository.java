package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;

public interface PlanRepository {
    PersistenceResult<Plan> create(Plan plan);

    PersistenceResult<Plan> find(PlanId planId);

    PersistenceResult<Plan> appendRevision(
            PlanId planId,
            long expectedCurrentRevisionNumber,
            PlanRevision revision);
}
