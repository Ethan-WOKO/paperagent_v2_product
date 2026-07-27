package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class ProductLeaseRepositoryAdapter implements LeaseRepository {
    private final ProductLeaseTransactions transactions;

    public ProductLeaseRepositoryAdapter(ProductLeaseTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<LeaseRecord> acquire(
            PlanId planId, String ownerId, String leaseToken, Instant expiresAt) {
        PersistenceResult<LeaseRecord> invalid =
                validateAcquire(planId, ownerId, leaseToken, expiresAt);
        if (invalid != null) {
            return invalid;
        }
        try {
            return transactions.acquire(planId, ownerId, leaseToken, expiresAt);
        } catch (RuntimeException failure) {
            if (!isConstraintFailure(failure)) {
                throw failure;
            }
            if (transactions.tokenExists(leaseToken)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
            }
            throw failure;
        }
    }

    @Override
    public PersistenceResult<LeaseRecord> renew(
            PlanId planId, String leaseToken, Instant expiresAt) {
        if (planId == null) {
            return invalid("planId");
        }
        if (blank(leaseToken)) {
            return invalid("leaseToken");
        }
        if (expiresAt == null) {
            return invalid("expiresAt");
        }
        return transactions.renew(planId, leaseToken, expiresAt);
    }

    @Override
    public PersistenceResult<LeaseRecord> release(PlanId planId, String leaseToken) {
        if (planId == null) {
            return invalid("planId");
        }
        if (blank(leaseToken)) {
            return invalid("leaseToken");
        }
        return transactions.release(planId, leaseToken);
    }

    @Override
    public PersistenceResult<LeaseRecord> find(PlanId planId) {
        if (planId == null) {
            return invalid("planId");
        }
        return transactions.find(planId);
    }

    private static PersistenceResult<LeaseRecord> validateAcquire(
            PlanId planId, String ownerId, String leaseToken, Instant expiresAt) {
        if (planId == null) {
            return invalid("planId");
        }
        if (blank(ownerId)) {
            return invalid("ownerId");
        }
        if (blank(leaseToken)) {
            return invalid("leaseToken");
        }
        if (expiresAt == null) {
            return invalid("expiresAt");
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isConstraintFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException
                    || current instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static PersistenceResult<LeaseRecord> invalid(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.INVALID_ARGUMENT, path);
    }
}
