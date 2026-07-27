package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import org.springframework.stereotype.Repository;

@Repository
public final class ProductExecutionStartRecoveryRepositoryAdapter
        implements ExecutionStartRecoveryRepository {
    private final ProductExecutionStartRecoveryTransactions transactions;

    public ProductExecutionStartRecoveryRepositoryAdapter(
            ProductExecutionStartRecoveryTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<ExecutionStartRecoverySnapshot> inspect(
            PlanId planId) {
        if (planId == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        }
        return transactions.inspect(planId);
    }
}
