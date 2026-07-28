package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.ActiveStepReplanRepository;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class ProductActiveStepReplanRepositoryAdapter
        implements ActiveStepReplanRepository {
    private final ProductActiveStepReplanTransactions transactions;

    public ProductActiveStepReplanRepositoryAdapter(
            ProductActiveStepReplanTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<PersistedActiveStepReplan>
            supersedeAndReplan(ActiveStepReplanRequest request) {
        if (request == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "request");
        }
        try {
            return transactions.supersedeAndReplan(request);
        } catch (RuntimeException exception) {
            if (!constraintViolation(exception)) {
                throw exception;
            }
            return transactions.classify(request);
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
