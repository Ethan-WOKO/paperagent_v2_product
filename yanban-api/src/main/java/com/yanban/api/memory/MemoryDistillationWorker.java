package com.yanban.api.memory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "yanban.memory.distillation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class MemoryDistillationWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryDistillationWorker.class);
    private final MemoryDistillationTransactions transactions;
    private final MemoryDistillationConversationService conversations;
    private final MemoryDistillationModelExtractor extractor;
    private final TaskExecutor executor;
    private final AtomicBoolean busy = new AtomicBoolean();

    MemoryDistillationWorker(MemoryDistillationTransactions transactions,
                             MemoryDistillationConversationService conversations,
                             MemoryDistillationModelExtractor extractor,
                             @Qualifier("memoryDistillationExecutor") TaskExecutor executor) {
        this.transactions = transactions;
        this.conversations = conversations;
        this.extractor = extractor;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${yanban.memory.distillation.scan-millis:2000}")
    void scan() {
        if (!busy.compareAndSet(false, true)) return;
        MemoryDistillationTransactions.Work work = null;
        try {
            work = transactions.claim();
            if (work == null) {
                busy.set(false);
                return;
            }
            MemoryDistillationTransactions.Work claimed = work;
            executor.execute(() -> process(claimed));
        } catch (RuntimeException failure) {
            try {
                if (work != null) {
                    transactions.fail(work, "MEMORY_DISTILLATION_WORKER_UNAVAILABLE",
                            "长期记忆沉淀任务暂时不可用，请稍后重试");
                }
            } catch (RuntimeException recordFailure) {
                log.warn("memory_distillation outcome=dispatch_failure_not_recorded errorType={}",
                        recordFailure.getClass().getSimpleName());
            } finally {
                busy.set(false);
            }
            log.warn("memory_distillation outcome=dispatch_failed errorType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void process(MemoryDistillationTransactions.Work work) {
        try {
            List<MemoryDistillationConversationService.ConversationLine> lines = conversations.load(
                    work.userId(), work.fromMessageId(), work.throughMessageId());
            List<MemoryDistillationCandidate> candidates = extractor.extract(
                    work.userId(), work.jobId(), lines);
            transactions.succeed(work, candidates);
            log.info("memory_distillation jobId={} userId={} outcome=succeeded candidates={}",
                    work.jobId(), work.userId(), candidates.size());
        } catch (RuntimeException failure) {
            String code = safeCode(failure);
            transactions.fail(work, code, userMessage(code));
            log.warn("memory_distillation jobId={} userId={} outcome=failed errorType={}",
                    work.jobId(), work.userId(), failure.getClass().getSimpleName());
        } finally {
            busy.set(false);
        }
    }

    private String safeCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.matches("MEMORY_DISTILLATION_[A-Z_]+")) return message;
        return "MEMORY_DISTILLATION_MODEL_FAILED";
    }

    private String userMessage(String code) {
        return switch (code) {
            case "MEMORY_DISTILLATION_RESPONSE_EMPTY", "MEMORY_DISTILLATION_RESPONSE_INVALID" ->
                    "模型没有返回有效的记忆候选，请检查模型配置后重试";
            case "MEMORY_DISTILLATION_CURSOR_CHANGED" -> "对话增量游标已变化，请重新发起任务";
            default -> "长期记忆沉淀失败，请检查模型设置后重试";
        };
    }
}
