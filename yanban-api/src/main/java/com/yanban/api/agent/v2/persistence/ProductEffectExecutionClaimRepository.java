package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import org.springframework.stereotype.Repository;

@Repository
public class ProductEffectExecutionClaimRepository {
    private final ProductEffectExecutionClaimTransactions transactions;

    public ProductEffectExecutionClaimRepository(
            ProductEffectExecutionClaimTransactions transactions) {
        this.transactions = transactions;
    }

    public ProductEffectExecutionClaimResult execute(
            ProductEffectExecutionClaimRequest request) {
        return transactions.execute(request);
    }

    /**
     * Claims authority before an external operation, executes it without a
     * database transaction, then persists its terminal receipt atomically.
     */
    public ProductEffectExecutionClaimResult executeExternal(
            ProductEffectExecutionClaimRequest request) {
        var replay = transactions.claimOrReplay(request);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ExecutionReceipt receipt = request.execution().get();
        return transactions.complete(request, receipt);
    }
}
