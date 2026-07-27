package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

import java.time.Instant;

final class InMemoryLeaseRepository implements LeaseRepository {
    private final InMemoryState state;

    InMemoryLeaseRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<LeaseRecord> acquire(
            PlanId planId,
            String ownerId,
            String leaseToken,
            Instant expiresAt) {
        PersistenceResult<LeaseRecord> invalid =
                validateAcquire(planId, ownerId, leaseToken, expiresAt);
        if (invalid != null) {
            return invalid;
        }
        synchronized (state.monitor) {
            Instant effectiveNow = state.observeLeaseTime();
            if (!state.plans.containsKey(planId)) {
                return PersistenceChecks.notFound("planId");
            }
            LeaseRecord current = state.leases.get(planId);
            if (current != null && !current.isExpiredAt(effectiveNow)) {
                if (current.ownerId().equals(ownerId)
                        && current.leaseToken().equals(leaseToken)
                        && current.expiresAt().equals(expiresAt)) {
                    return PersistenceResult.replayed(current);
                }
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD, "planId");
            }
            if (!expiresAt.isAfter(effectiveNow)) {
                return PersistenceChecks.invalid("expiresAt");
            }
            if (state.usedLeaseTokens.contains(leaseToken)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
            }
            long fencingToken = state.fencingTokens.getOrDefault(planId, 0L) + 1;
            LeaseRecord acquired = new LeaseRecord(
                    planId, ownerId, leaseToken, fencingToken, effectiveNow, expiresAt);
            state.fencingTokens.put(planId, fencingToken);
            state.usedLeaseTokens.add(leaseToken);
            state.leases.put(planId, acquired);
            return PersistenceResult.applied(acquired);
        }
    }

    @Override
    public PersistenceResult<LeaseRecord> renew(
            PlanId planId,
            String leaseToken,
            Instant expiresAt) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.blank(leaseToken)) {
            return PersistenceChecks.invalid("leaseToken");
        }
        if (PersistenceChecks.missing(expiresAt)) {
            return PersistenceChecks.invalid("expiresAt");
        }
        synchronized (state.monitor) {
            Instant effectiveNow = state.observeLeaseTime();
            LeaseRecord current = state.leases.get(planId);
            if (current == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_NOT_HELD, "planId");
            }
            if (!current.leaseToken().equals(leaseToken)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
            }
            if (current.isExpiredAt(effectiveNow)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_EXPIRED, "planId");
            }
            if (current.expiresAt().equals(expiresAt)) {
                return PersistenceResult.replayed(current);
            }
            if (!expiresAt.isAfter(current.expiresAt())) {
                return PersistenceChecks.invalid("expiresAt");
            }
            LeaseRecord renewed = new LeaseRecord(
                    current.planId(),
                    current.ownerId(),
                    current.leaseToken(),
                    current.fencingToken(),
                    current.acquiredAt(),
                    expiresAt);
            state.leases.put(planId, renewed);
            return PersistenceResult.applied(renewed);
        }
    }

    @Override
    public PersistenceResult<LeaseRecord> release(
            PlanId planId,
            String leaseToken) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.blank(leaseToken)) {
            return PersistenceChecks.invalid("leaseToken");
        }
        synchronized (state.monitor) {
            Instant effectiveNow = state.observeLeaseTime();
            LeaseRecord current = state.leases.get(planId);
            if (current == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_NOT_HELD, "planId");
            }
            if (!current.leaseToken().equals(leaseToken)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
            }
            if (current.isExpiredAt(effectiveNow)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_EXPIRED, "planId");
            }
            state.leases.remove(planId);
            return PersistenceResult.applied(current);
        }
    }

    @Override
    public PersistenceResult<LeaseRecord> find(PlanId planId) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            Instant effectiveNow = state.observeLeaseTime();
            LeaseRecord current = state.leases.get(planId);
            if (current == null) {
                return PersistenceChecks.notFound("planId");
            }
            if (current.isExpiredAt(effectiveNow)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_EXPIRED, "planId");
            }
            return PersistenceResult.found(current);
        }
    }

    private static PersistenceResult<LeaseRecord> validateAcquire(
            PlanId planId,
            String ownerId,
            String leaseToken,
            Instant expiresAt) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.blank(ownerId)) {
            return PersistenceChecks.invalid("ownerId");
        }
        if (PersistenceChecks.blank(leaseToken)) {
            return PersistenceChecks.invalid("leaseToken");
        }
        if (PersistenceChecks.missing(expiresAt)) {
            return PersistenceChecks.invalid("expiresAt");
        }
        return null;
    }
}
