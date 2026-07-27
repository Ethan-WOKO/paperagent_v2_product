package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepActivationRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public final class ProductStepActivationRepositoryAdapter
        implements StepActivationRepository {
    private final ProductStepActivationTransactions transactions;

    public ProductStepActivationRepositoryAdapter(
            ProductStepActivationTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<PersistedStepActivation> activate(
            StepActivationRequest request) {
        if (request == null) {
            return PersistenceResult.rejected(
                    io.paperagent.v2.persistence.PersistenceErrorCode
                            .INVALID_ARGUMENT,
                    "request");
        }
        try {
            return transactions.activate(request);
        } catch (RuntimeException exception) {
            if (!constraintViolation(exception)) {
                throw exception;
            }
            PersistenceResult<PersistedStepActivation> classified =
                    transactions.classifyConstraint(request);
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
