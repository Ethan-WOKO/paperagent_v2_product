package io.paperagent.v2.contracts;

public record ReceiptId(String value) {
    public ReceiptId {
        value = Contracts.id(value, "receiptId");
    }
}
