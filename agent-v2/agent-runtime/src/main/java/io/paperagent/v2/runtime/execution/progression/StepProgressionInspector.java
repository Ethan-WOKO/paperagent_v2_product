package io.paperagent.v2.runtime.execution.progression;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;

@FunctionalInterface
public interface StepProgressionInspector {
    PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId);
}
