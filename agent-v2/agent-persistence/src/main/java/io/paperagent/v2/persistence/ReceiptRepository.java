package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ReceiptId;

public interface ReceiptRepository {
    PersistenceResult<ExecutionReceipt> append(ExecutionReceipt receipt);

    PersistenceResult<ExecutionReceipt> find(ReceiptId receiptId);
}
