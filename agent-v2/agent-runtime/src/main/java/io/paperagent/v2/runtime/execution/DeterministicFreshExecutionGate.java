package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;

public final class DeterministicFreshExecutionGate implements FreshExecutionGate {
    @Override
    public FreshExecutionDecision evaluate(
            PersistenceResult<PersistedPlanBootstrap> bootstrapResult) {
        PersistenceResult<PersistedPlanBootstrap> result =
                FreshExecutionGateValues.required(
                        bootstrapResult,
                        "freshExecutionGate.bootstrapResult");
        return switch (result.outcome()) {
            case APPLIED -> new FreshLeaseAdmissionEligible(
                    result.value().orElseThrow().plan().id());
            case REPLAYED -> new RecoveryRequired(
                    result.value().orElseThrow().plan().id());
            case REJECTED -> new BootstrapRejected(
                    result.failure().orElseThrow());
            case FOUND -> throw FreshExecutionGateValues.failure(
                    FreshExecutionGateValidationCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "freshExecutionGate.bootstrapResult.outcome",
                    "FOUND is not a valid Plan bootstrap outcome");
        };
    }
}
