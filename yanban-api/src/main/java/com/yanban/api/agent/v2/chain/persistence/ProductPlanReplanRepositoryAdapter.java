package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.persistence.PersistedPlanReplan;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanReplanRepository;
import io.paperagent.v2.persistence.PlanReplanRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class ProductPlanReplanRepositoryAdapter
        implements PlanReplanRepository {
    private final ProductPlanReplanTransactions transactions;

    public ProductPlanReplanRepositoryAdapter(
            ProductPlanReplanTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<PersistedPlanReplan> replan(
            PlanReplanRequest request) {
        if (request == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "request");
        }
        try {
            return transactions.replan(request);
        } catch (RuntimeException exception) {
            if (!constraintViolation(exception)) {
                throw exception;
            }
            PersistenceResult<PersistedPlanReplan> classified =
                    transactions.classify(request);
            if (classified != null) {
                return classified;
            }
            throw exception;
        }
    }

    private static boolean constraintViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException
                    || current instanceof ConstraintViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
