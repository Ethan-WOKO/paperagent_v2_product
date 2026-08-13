package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Product persistence adapter for body-free plan Validation bundles. */
@Repository
public class ProductChainValidationBundleRepositoryAdapter
        implements ChainValidationBundleRepository {
    private final ProductChainTransactions transactions;

    public ProductChainValidationBundleRepositoryAdapter(
            ProductChainTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public BundleAppendResult appendBundle(
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ValidationBundleRecord> bundle,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> sets) {
        return transactions.appendValidationBundle(bundle, sets);
    }

    @Override
    public Optional<ChainPersistenceRecords.ValidationBundleRecord> findBundle(
            String validationBundleId) {
        return transactions.find(
                "agent_v2_chain_validation_bundles",
                ChainPersistenceRecords.ValidationBundleRecord.class,
                Map.of("validation_bundle_id", validationBundleId));
    }

    @Override
    public List<ChainPersistenceRecords.ValidationBundleSetRecord>
            findBundleSets(String validationBundleId) {
        return transactions.findAll(
                "agent_v2_chain_validation_bundle_sets",
                ChainPersistenceRecords.ValidationBundleSetRecord.class,
                Map.of("validation_bundle_id", validationBundleId),
                "step_id");
    }
}
