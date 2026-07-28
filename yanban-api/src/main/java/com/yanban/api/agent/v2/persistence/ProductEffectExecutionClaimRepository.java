package com.yanban.api.agent.v2.persistence;

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
}
