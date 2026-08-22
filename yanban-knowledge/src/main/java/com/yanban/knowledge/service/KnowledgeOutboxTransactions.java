package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeUploadProperties;
import com.yanban.knowledge.domain.KbProcessingOutboxEvent;
import com.yanban.knowledge.domain.KbProcessingOutboxRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class KnowledgeOutboxTransactions {
    private final KbProcessingOutboxRepository outbox;
    private final KnowledgeUploadProperties properties;

    KnowledgeOutboxTransactions(KbProcessingOutboxRepository outbox, KnowledgeUploadProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional
    public List<KnowledgeOutboxDispatcher.OutboxMessage> claim() {
        Instant now = Instant.now();
        for (KbProcessingOutboxEvent stale : outbox.findByStatusAndUpdatedAtBefore(
                "DISPATCHING", now.minusSeconds(60))) {
            stale.recoverStale(now);
        }
        List<KbProcessingOutboxEvent> due = outbox.lockDue(List.of("PENDING", "RETRY"), now,
                PageRequest.of(0, properties.getOutboxBatchSize()));
        due.forEach(KbProcessingOutboxEvent::claim);
        outbox.saveAll(due);
        return due.stream().map(event -> new KnowledgeOutboxDispatcher.OutboxMessage(
                event.getEventId(), event.getAggregateKey(), event.getPayloadJson())).toList();
    }

    @Transactional
    public void markDispatched(String eventId) {
        outbox.findById(eventId).ifPresent(value -> {
            value.dispatched(Instant.now());
            outbox.save(value);
        });
    }

    @Transactional
    public void markFailed(String eventId, Exception error) {
        outbox.findById(eventId).ifPresent(value -> {
            value.failed(Instant.now(), properties.getOutboxMaxAttempts(), error.getMessage());
            outbox.save(value);
        });
    }
}
