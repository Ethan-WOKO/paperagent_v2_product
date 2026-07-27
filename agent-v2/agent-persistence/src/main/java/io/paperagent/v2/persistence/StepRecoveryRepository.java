package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

public interface StepRecoveryRepository {
    PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId);
}
