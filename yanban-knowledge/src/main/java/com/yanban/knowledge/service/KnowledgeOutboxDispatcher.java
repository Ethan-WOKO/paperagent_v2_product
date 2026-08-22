package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeUploadProperties;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeOutboxDispatcher {
    private final KafkaTemplate<String, String> kafka;
    private final KnowledgeUploadProperties properties;
    private final KnowledgeOutboxTransactions transactions;
    public KnowledgeOutboxDispatcher(KafkaTemplate<String, String> kafka,
                                     KnowledgeOutboxTransactions transactions,
                                     KnowledgeUploadProperties properties) {
        this.kafka = kafka; this.transactions = transactions; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yanban.knowledge.upload.outbox-delay-millis:500}")
    public void dispatchDue() {
        for (OutboxMessage message : transactions.claim()) {
            try {
                kafka.send(properties.getProcessingTopic(), message.aggregateKey(), message.payload())
                        .get(15, TimeUnit.SECONDS);
                transactions.markDispatched(message.eventId());
                KnowledgeMetrics.outbox("dispatched");
            } catch (Exception ex) {
                transactions.markFailed(message.eventId(), ex);
                KnowledgeMetrics.outbox("failed");
            }
        }
    }

    record OutboxMessage(String eventId, String aggregateKey, String payload) {}
}
