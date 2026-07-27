package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Repository
class ProductLeaseTransactions {
    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductLeaseJpaRepository leases;
    private final ProductLeaseTimeSource timeSource;
    private final EntityManager entityManager;

    ProductLeaseTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductLeaseJpaRepository leases,
            ProductLeaseTimeSource timeSource,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.leases = leases;
        this.timeSource = timeSource;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<LeaseRecord> acquire(
            PlanId planId, String ownerId, String leaseToken, Instant expiresAt) {
        Instant canonicalExpiry = canonical(expiresAt);
        if (bootstraps.lockByPlanId(planId.value()).isEmpty()) {
            return rejected(PersistenceErrorCode.NOT_FOUND, "planId");
        }
        Instant effectiveNow = canonical(timeSource.observe());
        Optional<ProductLeaseEntity> current =
                leases.findFirstByPlanIdOrderByFencingTokenDesc(planId.value());
        if (current.isPresent() && active(current.get(), effectiveNow)) {
            ProductLeaseEntity held = current.get();
            if (held.ownerId().equals(ownerId)
                    && held.leaseToken().equals(leaseToken)
                    && held.expiresAt().equals(canonicalExpiry)) {
                return PersistenceResult.replayed(record(held));
            }
            return rejected(PersistenceErrorCode.LEASE_HELD, "planId");
        }
        if (!canonicalExpiry.isAfter(effectiveNow)) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT, "expiresAt");
        }
        if (leases.findByLeaseToken(leaseToken).isPresent()) {
            return rejected(PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
        }
        long nextFence = current.map(row -> row.fencingToken() + 1).orElse(1L);
        ProductLeaseEntity acquired = new ProductLeaseEntity(
                planId.value(),
                nextFence,
                ownerId,
                leaseToken,
                effectiveNow,
                canonicalExpiry);
        entityManager.persist(acquired);
        entityManager.flush();
        return PersistenceResult.applied(record(acquired));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<LeaseRecord> renew(
            PlanId planId, String leaseToken, Instant expiresAt) {
        Instant canonicalExpiry = canonical(expiresAt);
        Authority authority = authority(planId);
        if (!authority.exists()) {
            return rejected(PersistenceErrorCode.LEASE_NOT_HELD, "planId");
        }
        ProductLeaseEntity current = authority.current();
        if (current == null || current.releasedAt() != null) {
            return rejected(PersistenceErrorCode.LEASE_NOT_HELD, "planId");
        }
        if (!current.leaseToken().equals(leaseToken)) {
            return rejected(PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
        }
        if (!authority.now().isBefore(current.expiresAt())) {
            return rejected(PersistenceErrorCode.LEASE_EXPIRED, "planId");
        }
        if (current.expiresAt().equals(canonicalExpiry)) {
            return PersistenceResult.replayed(record(current));
        }
        if (!canonicalExpiry.isAfter(current.expiresAt())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT, "expiresAt");
        }
        current.renewUntil(canonicalExpiry);
        entityManager.flush();
        return PersistenceResult.applied(record(current));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<LeaseRecord> release(PlanId planId, String leaseToken) {
        Authority authority = authority(planId);
        if (!authority.exists()) {
            return rejected(PersistenceErrorCode.LEASE_NOT_HELD, "planId");
        }
        ProductLeaseEntity current = authority.current();
        if (current == null || current.releasedAt() != null) {
            return rejected(PersistenceErrorCode.LEASE_NOT_HELD, "planId");
        }
        if (!current.leaseToken().equals(leaseToken)) {
            return rejected(PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
        }
        if (!authority.now().isBefore(current.expiresAt())) {
            return rejected(PersistenceErrorCode.LEASE_EXPIRED, "planId");
        }
        LeaseRecord released = record(current);
        current.releaseAt(authority.now());
        entityManager.flush();
        return PersistenceResult.applied(released);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<LeaseRecord> find(PlanId planId) {
        Authority authority = authority(planId);
        if (!authority.exists()) {
            return rejected(PersistenceErrorCode.NOT_FOUND, "planId");
        }
        ProductLeaseEntity current = authority.current();
        if (current == null || current.releasedAt() != null) {
            return rejected(PersistenceErrorCode.NOT_FOUND, "planId");
        }
        if (!authority.now().isBefore(current.expiresAt())) {
            return rejected(PersistenceErrorCode.LEASE_EXPIRED, "planId");
        }
        return PersistenceResult.found(record(current));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean tokenExists(String leaseToken) {
        return leases.findByLeaseToken(leaseToken).isPresent();
    }

    private Authority authority(PlanId planId) {
        if (bootstraps.lockByPlanId(planId.value()).isEmpty()) {
            return Authority.missing();
        }
        Instant now = canonical(timeSource.observe());
        ProductLeaseEntity current =
                leases.findFirstByPlanIdOrderByFencingTokenDesc(planId.value()).orElse(null);
        return new Authority(true, now, current);
    }

    private static boolean active(ProductLeaseEntity lease, Instant now) {
        return lease.releasedAt() == null && now.isBefore(lease.expiresAt());
    }

    private static Instant canonical(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static LeaseRecord record(ProductLeaseEntity entity) {
        return new LeaseRecord(
                new PlanId(entity.planId()),
                entity.ownerId(),
                entity.leaseToken(),
                entity.fencingToken(),
                entity.acquiredAt(),
                entity.expiresAt());
    }

    private static <T> PersistenceResult<T> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private record Authority(boolean exists, Instant now, ProductLeaseEntity current) {
        static Authority missing() {
            return new Authority(false, null, null);
        }
    }
}
