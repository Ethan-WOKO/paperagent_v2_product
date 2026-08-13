package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskAuthorityFact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact Chain AuthorityEvent prefix and formal-record visibility checks. */
final class ProductValidationPublishAuthorityCut {
    private final ChainFoundationRepository foundations;

    ProductValidationPublishAuthorityCut(ChainFoundationRepository foundations) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
    }

    Prefix prefix(String taskId) {
        long cut = foundations.highestAuthorityEventSequence(taskId);
        Map<String, Long> result = new HashMap<>();
        Map<String, ChainPersistenceRecords.AuthorityEventRecord> events =
                new HashMap<>();
        long prior = 0;
        for (var event : foundations.findAuthorityEvents(taskId, cut)) {
            if (!event.taskId().equals(taskId)
                    || event.eventSequence() <= prior
                    || event.eventSequence() > cut
                    || result.put(event.eventId(), event.eventSequence()) != null
                    || events.put(event.eventId(), event) != null) {
                throw blocked("task authority event prefix is inconsistent");
            }
            prior = event.eventSequence();
        }
        return new Prefix(cut, Map.copyOf(result), Map.copyOf(events));
    }

    static <T extends Record & TaskAuthorityFact> List<T> records(
            String taskId, List<T> values, Map<String, Long> sequences) {
        List<T> result = new ArrayList<>();
        for (T value : values) {
            visible(value, taskId, sequences);
            result.add(value);
        }
        return List.copyOf(result);
    }

    static void visible(
            TaskAuthorityFact value, String taskId, Map<String, Long> sequences) {
        if (!value.taskId().equals(taskId)
                || !sequences.containsKey(value.eventId())) {
            throw blocked("authority record lacks its exact formal task event");
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.VALIDATION_AND_PUBLISH, reason);
    }

    record Prefix(
            long eventCut,
            Map<String, Long> sequences,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
    }
}
