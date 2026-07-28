package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.ReceiptRepository;
import org.springframework.stereotype.Repository;

@Repository
public class ProductReceiptRepositoryAdapter
        implements ReceiptRepository {
    private final ProductReceiptTransactions transactions;

    public ProductReceiptRepositoryAdapter(
            ProductReceiptTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<ExecutionReceipt> append(
            ExecutionReceipt receipt) {
        if (receipt == null) {
            return invalid("receipt");
        }
        PersistenceResult<ExecutionReceipt> durable =
                transactions.replay(receipt);
        if (durable != null) {
            return durable;
        }
        try {
            return transactions.append(receipt);
        } catch (RuntimeException exception) {
            if (!ProductReceiptRaceFailure.recognized(exception)) {
                throw exception;
            }
            PersistenceResult<ExecutionReceipt> classified =
                    transactions.classifyAndAppend(receipt);
            if (classified != null) {
                return classified;
            }
            throw exception;
        }
    }

    @Override
    public PersistenceResult<ExecutionReceipt> find(ReceiptId receiptId) {
        return receiptId == null
                ? invalid("receiptId")
                : transactions.find(receiptId);
    }

    private static PersistenceResult<ExecutionReceipt> invalid(String path) {
        return PersistenceResult.rejected(
                PersistenceErrorCode.INVALID_ARGUMENT, path);
    }
}
