package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.StepPauseRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.function.Supplier;

@Repository
public class ProductStepInterruptionRepositoryAdapter
        implements StepInterruptionRepository {
    private final ProductStepInterruptionTransactions transactions;

    public ProductStepInterruptionRepositoryAdapter(
            ProductStepInterruptionTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<PersistedStepInterruption> pause(
            StepPauseRequest request) {
        return request == null ? invalid() : invoke(
                () -> transactions.pause(request),
                () -> transactions.classifyPause(request));
    }

    @Override
    public PersistenceResult<PersistedStepInterruption> fail(
            StepFailRequest request) {
        return request == null ? invalid() : invoke(
                () -> transactions.fail(request),
                () -> transactions.classifyFail(request));
    }

    @Override
    public PersistenceResult<PersistedStepInterruption> cancel(
            StepCancelRequest request) {
        return request == null ? invalid() : invoke(
                () -> transactions.cancel(request),
                () -> transactions.classifyCancel(request));
    }

    private static PersistenceResult<PersistedStepInterruption> invoke(
            Supplier<PersistenceResult<PersistedStepInterruption>> write,
            Supplier<PersistenceResult<PersistedStepInterruption>> classify) {
        try {
            return write.get();
        } catch (RuntimeException exception) {
            if (!constraintViolation(exception)) {
                throw exception;
            }
            PersistenceResult<PersistedStepInterruption> result = classify.get();
            if (result != null) {
                return result;
            }
            throw exception;
        }
    }

    private static PersistenceResult<PersistedStepInterruption> invalid() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.INVALID_ARGUMENT, "request");
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
