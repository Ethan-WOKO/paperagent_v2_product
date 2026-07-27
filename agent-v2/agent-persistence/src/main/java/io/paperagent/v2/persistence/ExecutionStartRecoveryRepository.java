package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

public interface ExecutionStartRecoveryRepository {
    PersistenceResult<ExecutionStartRecoverySnapshot> inspect(PlanId planId);
}
