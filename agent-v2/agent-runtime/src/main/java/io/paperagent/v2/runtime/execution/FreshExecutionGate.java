package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;

@FunctionalInterface
public interface FreshExecutionGate {
    FreshExecutionDecision evaluate(
            PersistenceResult<PersistedPlanBootstrap> bootstrapResult);
}
