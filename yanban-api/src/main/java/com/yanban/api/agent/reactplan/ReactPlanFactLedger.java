package com.yanban.api.agent.reactplan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable fold of append-only tool facts. Exact replay is a no-op; identity
 * reuse with different content is a conflict.
 */
public final class ReactPlanFactLedger {
    private final List<ReactPlanFact> facts;
    private final Map<String, ReactPlanToolRequested> requests;
    private final Map<String, ReactPlanReceiptRecorded> receipts;

    private ReactPlanFactLedger(List<ReactPlanFact> facts) {
        this.facts = List.copyOf(facts);
        this.requests = new LinkedHashMap<>();
        this.receipts = new LinkedHashMap<>();
        for (ReactPlanFact fact : this.facts) {
            fold(fact);
        }
    }

    public static ReactPlanFactLedger empty() {
        return new ReactPlanFactLedger(List.of());
    }

    public static ReactPlanFactLedger rebuild(List<ReactPlanFact> facts) {
        Objects.requireNonNull(facts, "facts");
        return new ReactPlanFactLedger(facts);
    }

    public ReactPlanFactLedger append(ReactPlanFact fact) {
        Objects.requireNonNull(fact, "fact");
        if (isExactReplay(fact)) {
            return this;
        }
        java.util.ArrayList<ReactPlanFact> appended = new java.util.ArrayList<>(facts);
        appended.add(fact);
        return new ReactPlanFactLedger(appended);
    }

    public List<ReactPlanFact> facts() {
        return facts;
    }

    public List<ReactPlanToolRequested> requests() {
        return List.copyOf(requests.values());
    }

    public List<ReactPlanReceiptRecorded> receipts() {
        return List.copyOf(receipts.values());
    }

    public boolean hasPendingEffects() {
        return requests.keySet().stream().anyMatch(callId -> !receipts.containsKey(callId));
    }

    public Optional<ReactPlanReceiptRecorded> receiptFor(String toolCallId) {
        return Optional.ofNullable(receipts.get(toolCallId));
    }

    private boolean isExactReplay(ReactPlanFact fact) {
        if (fact instanceof ReactPlanToolRequested requested) {
            ReactPlanToolRequested existing = requests.get(requested.toolCallId());
            if (existing == null) {
                return false;
            }
            if (existing.equals(requested)) {
                return true;
            }
            throw conflict(requested.toolCallId(), "tool request content changed");
        }
        ReactPlanReceiptRecorded receipt = (ReactPlanReceiptRecorded) fact;
        ReactPlanReceiptRecorded existing = receipts.get(receipt.toolCallId());
        if (existing == null) {
            return false;
        }
        if (existing.equals(receipt)) {
            return true;
        }
        throw conflict(receipt.toolCallId(), "terminal Receipt changed");
    }

    private void fold(ReactPlanFact fact) {
        if (fact instanceof ReactPlanToolRequested requested) {
            ReactPlanToolRequested existing = requests.putIfAbsent(requested.toolCallId(), requested);
            if (existing != null && !existing.equals(requested)) {
                throw conflict(requested.toolCallId(), "tool request content changed");
            }
            return;
        }
        ReactPlanReceiptRecorded receipt = (ReactPlanReceiptRecorded) fact;
        ReactPlanToolRequested request = requests.get(receipt.toolCallId());
        if (request == null) {
            throw conflict(receipt.toolCallId(), "Receipt has no preceding tool request");
        }
        if (!request.requestDigest().equals(receipt.requestDigest())) {
            throw conflict(receipt.toolCallId(), "Receipt request digest does not match");
        }
        ReactPlanReceiptRecorded existing = receipts.putIfAbsent(receipt.toolCallId(), receipt);
        if (existing != null && !existing.equals(receipt)) {
            throw conflict(receipt.toolCallId(), "more than one terminal Receipt exists");
        }
    }

    private static ReactPlanFactConflictException conflict(String callId, String reason) {
        return new ReactPlanFactConflictException("toolCallId " + callId + ": " + reason);
    }
}
