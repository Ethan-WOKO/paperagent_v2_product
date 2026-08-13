package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainCandidateMaterializationFailureRepository;
import io.paperagent.v2.chain.ChainCandidateMaterializationFailureWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ProductChainCandidateMaterializationFailureRepositoryAdapter
        implements ChainCandidateMaterializationFailureRepository,
        ChainCandidateMaterializationFailureWriter {
    private static final String TABLE =
            "agent_v2_chain_candidate_materialization_failures";
    private final ProductChainTransactions transactions;

    public ProductChainCandidateMaterializationFailureRepositoryAdapter(
            ProductChainTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public Optional<ChainPersistenceRecords.CandidateMaterializationFailureRecord>
            findCandidateMaterializationFailure(String taskId, String actionId) {
        return transactions.find(TABLE,
                ChainPersistenceRecords.CandidateMaterializationFailureRecord.class,
                ordered("task_id", taskId, "action_id", actionId));
    }

    @Override
    public ChainPersistenceRecords.AuthoritativeAppendResult<
            ChainPersistenceRecords.CandidateMaterializationFailureRecord>
            appendCandidateMaterializationFailure(
                    ChainPersistenceRecords.AuthoritativeFact<
                            ChainPersistenceRecords.CandidateMaterializationFailureRecord>
                            failure) {
        var fact = failure.fact();
        return transactions.appendAuthoritative(TABLE,
                ChainPersistenceRecords.CandidateMaterializationFailureRecord.class,
                failure, ordered("task_id", fact.taskId(),
                        "action_id", fact.actionId()));
    }

    private static Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
