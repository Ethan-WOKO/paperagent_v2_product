package com.yanban.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbProcessingOutboxEvent;
import com.yanban.knowledge.domain.KbProcessingOutboxRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeOutboxService {
    private final KbProcessingOutboxRepository outbox;
    private final ObjectMapper json;
    public KnowledgeOutboxService(KbProcessingOutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox; this.json = json;
    }

    public KbProcessingOutboxEvent enqueue(KbDocument document) {
        String eventId = UUID.randomUUID().toString();
        FileProcessingMessage message = new FileProcessingMessage(eventId, document.getId(),
                document.getUserId(), document.getObjectKey(), document.getFileDigest());
        try {
            KbProcessingOutboxEvent event = new KbProcessingOutboxEvent(eventId, document.getId(),
                    document.getUserId(), document.getId().toString(), json.writeValueAsString(message));
            document.setProcessingEventId(eventId);
            return outbox.save(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize knowledge processing event", ex);
        }
    }
}
