package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import org.springframework.stereotype.Repository;

@Repository
public class ProductStepRecoveryRepositoryAdapter
        implements StepRecoveryRepository {
    private final ProductStepRecoveryTransactions transactions;

    public ProductStepRecoveryRepositoryAdapter(
            ProductStepRecoveryTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId) {
        if (planId == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        }
        return transactions.inspect(planId);
    }
}
