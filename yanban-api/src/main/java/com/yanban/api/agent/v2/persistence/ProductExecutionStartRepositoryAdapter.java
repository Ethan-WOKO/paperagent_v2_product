package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public final class ProductExecutionStartRepositoryAdapter
        implements ExecutionStartRepository {
    private final ProductExecutionStartTransactions transactions;

    public ProductExecutionStartRepositoryAdapter(
            ProductExecutionStartTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<PersistedExecutionStart> start(
            ExecutionStartRequest request) {
        if (request == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "request");
        }
        try {
            return transactions.start(request);
        } catch (RuntimeException exception) {
            if (!constraintViolation(exception)) {
                throw exception;
            }
            if (transactions.eventIdExists(request.startEvent().id().value())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.startEvent.id");
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
