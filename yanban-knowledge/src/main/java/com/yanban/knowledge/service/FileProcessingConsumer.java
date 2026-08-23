package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FileProcessingConsumer {

    private final ObjectMapper objectMapper;
    private final FileProcessingService fileProcessingService;

    public FileProcessingConsumer(ObjectMapper objectMapper, FileProcessingService fileProcessingService) {
        this.objectMapper = objectMapper;
        this.fileProcessingService = fileProcessingService;
    }

    @KafkaListener(topics = "${yanban.knowledge.upload.processing-topic:file-processing}",
            groupId = "yanban-kb-processing", containerFactory = "knowledgeKafkaListenerContainerFactory")
    public void onRecord(ConsumerRecord<String, String> record) throws Exception {
        FileProcessingMessage message = objectMapper.readValue(record.value(), FileProcessingMessage.class);
        if (record.key() == null || !record.key().equals(message.documentId().toString())) {
            throw new KnowledgePermanentProcessingException("Kafka Key 与 Document ID 不一致");
        }
        fileProcessingService.process(message);
    }

    public void onMessage(String payload) throws Exception {
        try {
            FileProcessingMessage message = objectMapper.readValue(payload, FileProcessingMessage.class);
            fileProcessingService.process(message);
        } catch (Exception ex) {
            throw ex;
        }
    }
}
