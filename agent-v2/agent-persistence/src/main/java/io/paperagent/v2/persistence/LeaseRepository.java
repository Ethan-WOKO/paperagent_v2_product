package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

import java.time.Instant;

public interface LeaseRepository {
    PersistenceResult<LeaseRecord> acquire(
            PlanId planId,
            String ownerId,
            String leaseToken,
            Instant expiresAt);

    PersistenceResult<LeaseRecord> renew(
            PlanId planId,
            String leaseToken,
            Instant expiresAt);

    PersistenceResult<LeaseRecord> release(PlanId planId, String leaseToken);

    PersistenceResult<LeaseRecord> find(PlanId planId);
}
