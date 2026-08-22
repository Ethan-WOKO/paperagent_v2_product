package com.yanban.knowledge.service;

import com.yanban.knowledge.domain.KbProcessingDeadLetterRepository;
import com.yanban.knowledge.domain.KbProcessingOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class KnowledgeBacklogMetrics {
    KnowledgeBacklogMetrics(MeterRegistry registry,
                            KbProcessingOutboxRepository outbox,
                            KbProcessingDeadLetterRepository deadLetters) {
        Gauge.builder("yanban.knowledge.outbox.backlog", outbox,
                        repository -> repository.countByStatusIn(List.of("PENDING", "RETRY", "DISPATCHING")))
                .description("Knowledge processing events waiting for Kafka dispatch")
                .register(registry);
        Gauge.builder("yanban.knowledge.dead.letters.pending", deadLetters,
                        repository -> repository.countByStatus("PENDING"))
                .description("Knowledge processing dead letters waiting for redrive")
                .register(registry);
    }
}
