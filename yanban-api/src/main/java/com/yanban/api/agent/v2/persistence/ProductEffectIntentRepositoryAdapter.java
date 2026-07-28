package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import org.springframework.stereotype.Repository;

@Repository
public class ProductEffectIntentRepositoryAdapter
        implements EffectIntentRepository {
    private final ProductEffectIntentTransactions transactions;
    private final ProductStepRecoveryTransactions recovery;

    public ProductEffectIntentRepositoryAdapter(
            ProductEffectIntentTransactions transactions,
            ProductStepRecoveryTransactions recovery) {
        this.transactions = transactions;
        this.recovery = recovery;
    }

    @Override
    public PersistenceResult<PersistedEffectIntent> persist(
            EffectIntentRequest request) {
        if (request == null) {
            return invalid("request");
        }
        PersistenceResult<PersistedEffectIntent> durable =
                transactions.replay(request);
        if (durable != null) {
            return durable;
        }
        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectWriterAuthority(
                        request.intent().planId());
        if (!inspected.successful()) {
            var failure = inspected.failure().orElseThrow();
            return PersistenceResult.rejected(failure.code(), failure.path());
        }
        PersistedStepRecoveryActive active =
                (PersistedStepRecoveryActive) inspected.value().orElseThrow();
        try {
            return transactions.persist(request, active);
        } catch (RuntimeException exception) {
            if (!ProductReceiptRaceFailure.recognized(exception)) {
                throw exception;
            }
            PersistenceResult<PersistedEffectIntent> classified =
                    transactions.replay(request);
            if (classified != null) {
                return classified;
            }
            throw exception;
        }
    }

    @Override
    public PersistenceResult<PersistedEffectIntent> find(
            ToolCallId toolCallId) {
        return toolCallId == null
                ? invalid("toolCallId")
                : transactions.find(toolCallId);
    }

    private static PersistenceResult<PersistedEffectIntent> invalid(
            String path) {
        return PersistenceResult.rejected(
                PersistenceErrorCode.INVALID_ARGUMENT, path);
    }

}
