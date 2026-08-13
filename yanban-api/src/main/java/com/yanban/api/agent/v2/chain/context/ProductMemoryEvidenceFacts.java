package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;

/** Frozen formal evidence catalog for module 11. */
record ProductMemoryEvidenceFacts(
        ChainPersistenceRecords.ContextRevisionRecord building,
        long taskEventCut,
        List<EvidenceEntry> entries) {
    ProductMemoryEvidenceFacts {
        entries = List.copyOf(entries);
    }

    boolean empty() {
        return entries.isEmpty();
    }

    record EvidenceEntry(
            String kind,
            String authorityRef,
            String digest,
            long taskEventSequence,
            String planRevisionId,
            String stepId,
            String activationEventId,
            boolean acceptedDelivery,
            Map<String, ChainContextValue> details) {
        EvidenceEntry {
            details = Map.copyOf(details);
        }
    }
}
