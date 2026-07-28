package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public record ProductEffectExecutionClaimRequest(
        PersistedStepRecoveryActive recovery,
        LeaseRecord lease,
        PersistedEffectIntent intent,
        String leaseToken,
        long fencingToken,
        Instant observedAt,
        Supplier<ExecutionReceipt> execution) {
    public ProductEffectExecutionClaimRequest {
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(intent, "intent");
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new IllegalArgumentException("leaseToken must not be blank");
        }
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(execution, "execution");
    }
}
