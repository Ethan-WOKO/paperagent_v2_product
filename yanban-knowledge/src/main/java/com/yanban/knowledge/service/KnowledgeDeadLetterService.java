package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.domain.KbProcessingDeadLetter;
import com.yanban.knowledge.domain.KbProcessingDeadLetterRepository;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeDeadLetterService {
    private final KbProcessingDeadLetterRepository deadLetters;
    private final KbDocumentRepository documents;
    private final KnowledgeOutboxService outbox;
    private final ObjectMapper json;

    public KnowledgeDeadLetterService(KbProcessingDeadLetterRepository deadLetters,
                                      KbDocumentRepository documents,
                                      KnowledgeOutboxService outbox,
                                      ObjectMapper json) {
        this.deadLetters = deadLetters; this.documents = documents; this.outbox = outbox; this.json = json;
    }

    @KafkaListener(topics = "${yanban.knowledge.upload.processing-topic:file-processing}.DLT",
            groupId = "yanban-kb-processing-dlt")
    @Transactional
    public void capture(ConsumerRecord<String, String> record) throws Exception {
        FileProcessingMessage message = json.readValue(record.value(), FileProcessingMessage.class);
        if (deadLetters.findByOriginalEventId(message.eventId()).isPresent()) return;
        String errorType = header(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        String errorMessage = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        int retries = intHeader(record, KafkaHeaders.DELIVERY_ATTEMPT);
        deadLetters.save(new KbProcessingDeadLetter(message.eventId(), message.documentId(), record.key(),
                record.value(), errorType, errorMessage, retries));
        KnowledgeMetrics.deadLetter("captured");
        documents.findById(message.documentId()).ifPresent(document -> {
            if (!"READY".equals(document.getStatus())) {
                document.setStatus("FAILED");
                document.setErrorMessage(limit(errorMessage));
                documents.save(document);
            }
        });
    }

    public List<KbProcessingDeadLetter> pending() {
        return deadLetters.findTop100ByStatusOrderByCreatedAtAsc("PENDING");
    }

    @Transactional
    public String redrive(long id) {
        KbProcessingDeadLetter dead = deadLetters.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dead letter not found"));
        if (!"PENDING".equals(dead.getStatus())) return dead.getStatus();
        var document = documents.findById(dead.getDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        document.setStatus("PROCESSING");
        document.setErrorMessage(null);
        var event = outbox.enqueue(document);
        documents.save(document);
        dead.redriven(event.getEventId(), Instant.now());
        deadLetters.save(dead);
        KnowledgeMetrics.deadLetter("redriven");
        return event.getEventId();
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
    private int intHeader(ConsumerRecord<String, String> record, String name) {
        var value = record.headers().lastHeader(name);
        if (value == null || value.value() == null) return 0;
        if (value.value().length == Integer.BYTES) {
            return java.nio.ByteBuffer.wrap(value.value()).getInt();
        }
        try {
            return Integer.parseInt(new String(value.value(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
    private String limit(String value) {
        if (value == null || value.isBlank()) return "文件处理多次失败，已进入 DLQ";
        return value.substring(0, Math.min(255, value.length()));
    }
}
