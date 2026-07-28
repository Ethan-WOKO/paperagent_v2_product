package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ReceiptId;

import java.util.Optional;

/** Read-only receipt boundary used to construct an authoritative synthesis. */
public interface FinalSynthesisReceiptSource {
    Optional<ExecutionReceipt> find(ReceiptId receiptId);
}
