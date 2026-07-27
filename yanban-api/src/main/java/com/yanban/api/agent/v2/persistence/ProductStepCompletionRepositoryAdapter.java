package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRepository;
import io.paperagent.v2.persistence.StepCompletionRequest;
import org.springframework.stereotype.Repository;

@Repository
public final class ProductStepCompletionRepositoryAdapter
        implements StepCompletionRepository {
    private final ProductStepCompletionTransactions transactions;

    public ProductStepCompletionRepositoryAdapter(
            ProductStepCompletionTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<PersistedStepCompletion> complete(
            StepCompletionRequest request) {
        if (request == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "request");
        }
        try {
            return transactions.complete(request);
        } catch (RuntimeException exception) {
            if (!ProductReceiptRaceFailure.recognized(exception)) {
                throw exception;
            }
            PersistenceResult<PersistedStepCompletion> classified =
                    transactions.classify(request);
            if (classified != null) {
                return classified;
            }
            throw exception;
        }
    }
}
