package com.yanban.api.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "yanban.memory.distillation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class MemoryDistillationScheduler {
    private static final Logger log = LoggerFactory.getLogger(MemoryDistillationScheduler.class);
    private final MemoryDistillationTransactions transactions;
    private final MemoryDistillationService service;

    MemoryDistillationScheduler(MemoryDistillationTransactions transactions,
                                MemoryDistillationService service) {
        this.transactions = transactions;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${yanban.memory.distillation.schedule-scan-millis:60000}")
    void scheduleDueUsers() {
        for (Long userId : transactions.dueUsers()) {
            try {
                service.requestAutomatic(userId);
            } catch (RuntimeException failure) {
                log.warn("memory_distillation_schedule userId={} outcome=failed errorType={}",
                        userId, failure.getClass().getSimpleName());
            }
        }
    }
}
