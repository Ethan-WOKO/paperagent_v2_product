package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ReceiptId;

final class InMemoryReceiptRepository implements ReceiptRepository {
    private final InMemoryState state;

    InMemoryReceiptRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<ExecutionReceipt> append(ExecutionReceipt receipt) {
        if (PersistenceChecks.missing(receipt)) {
            return PersistenceChecks.invalid("receipt");
        }
        synchronized (state.monitor) {
            if (state.effectIntents.containsKey(receipt.toolCallId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                        "receipt.toolCallId");
            }
            ExecutionReceipt existing = state.receipts.get(receipt.id());
            if (existing == null) {
                state.receipts.put(receipt.id(), receipt);
                return PersistenceResult.applied(receipt);
            }
            return existing.equals(receipt)
                    ? PersistenceResult.replayed(existing)
                    : PersistenceResult.rejected(
                            PersistenceErrorCode.CONFLICTING_REPLAY, "receipt.id");
        }
    }

    @Override
    public PersistenceResult<ExecutionReceipt> find(ReceiptId receiptId) {
        if (PersistenceChecks.missing(receiptId)) {
            return PersistenceChecks.invalid("receiptId");
        }
        synchronized (state.monitor) {
            ExecutionReceipt receipt = state.receipts.get(receiptId);
            return receipt == null
                    ? PersistenceChecks.notFound("receiptId")
                    : PersistenceResult.found(receipt);
        }
    }
}
