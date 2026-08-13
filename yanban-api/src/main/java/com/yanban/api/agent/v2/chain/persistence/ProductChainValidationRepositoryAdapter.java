package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Product persistence adapter for body-free typed chain Validations. */
@Repository
public class ProductChainValidationRepositoryAdapter
        implements ChainValidationRepository {
    private final ProductChainTransactions transactions;

    public ProductChainValidationRepositoryAdapter(
            ProductChainTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public ValidationAppendResult appendValidation(
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ValidationSetRecord> validation,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionReceiptItems) {
        return transactions.appendValidation(
                validation, candidateItems, actionReceiptItems);
    }

    @Override
    public Optional<ChainPersistenceRecords.ValidationSetRecord>
            findValidation(String validationId) {
        return transactions.find(
                "agent_v2_chain_validation_sets",
                ChainPersistenceRecords.ValidationSetRecord.class,
                Map.of("validation_id", validationId));
    }

    @Override
    public List<ChainPersistenceRecords.CandidateValidationItemRecord>
            findCandidateItems(String validationId) {
        return transactions.findAll(
                "agent_v2_chain_candidate_validation_items",
                ChainPersistenceRecords.CandidateValidationItemRecord.class,
                Map.of("validation_id", validationId), "requirement_id");
    }

    @Override
    public List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
            findActionReceiptItems(String validationId) {
        return transactions.findAll(
                "agent_v2_chain_action_receipt_validation_items",
                ChainPersistenceRecords.ActionReceiptValidationItemRecord.class,
                Map.of("validation_id", validationId), "requirement_id");
    }
}
