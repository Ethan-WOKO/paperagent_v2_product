package com.yanban.api.memory;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

@ExtendWith(MockitoExtension.class)
class MemoryDistillationWorkerTest {
    @Mock
    private MemoryDistillationTransactions transactions;

    @Mock
    private MemoryDistillationConversationService conversations;

    @Mock
    private MemoryDistillationModelExtractor extractor;

    @Test
    void idleScanDoesNotInvokeConversationOrModelServices() {
        MemoryDistillationWorker worker = worker(Runnable::run);
        when(transactions.claim()).thenReturn(null);

        worker.scan();

        verify(conversations, never()).load(42L, 0L, 5L);
        verify(extractor, never()).extract(42L, 9L, List.of());
    }

    @Test
    void claimedWorkRunsOnDedicatedExecutorAndCommitsCandidates() {
        MemoryDistillationTransactions.Work work = work();
        MemoryDistillationCandidate candidate = candidate();
        when(transactions.claim()).thenReturn(work);
        when(conversations.load(42L, 0L, 5L)).thenReturn(List.of());
        when(extractor.extract(42L, 9L, List.of())).thenReturn(List.of(candidate));

        worker(Runnable::run).scan();

        verify(transactions).succeed(work, List.of(candidate));
        verify(transactions, never()).fail(work,
                "MEMORY_DISTILLATION_MODEL_FAILED", "长期记忆沉淀失败，请检查模型设置后重试");
    }

    @Test
    void modelFailureIsRecordedWithoutEscapingTheWorker() {
        MemoryDistillationTransactions.Work work = work();
        when(transactions.claim()).thenReturn(work);
        when(conversations.load(42L, 0L, 5L)).thenReturn(List.of());
        when(extractor.extract(42L, 9L, List.of()))
                .thenThrow(new IllegalStateException("MEMORY_DISTILLATION_RESPONSE_INVALID"));

        worker(Runnable::run).scan();

        verify(transactions).fail(work, "MEMORY_DISTILLATION_RESPONSE_INVALID",
                "模型没有返回有效的记忆候选，请检查模型配置后重试");
        verify(transactions, never()).succeed(work, List.of());
    }

    @Test
    void candidateValidationFailureUsesAnAccurateUserMessage() {
        MemoryDistillationTransactions.Work work = work();
        when(transactions.claim()).thenReturn(work);
        when(conversations.load(42L, 0L, 5L)).thenReturn(List.of());
        when(extractor.extract(42L, 9L, List.of()))
                .thenThrow(new IllegalStateException("MEMORY_DISTILLATION_MIXED_SCOPE"));

        worker(Runnable::run).scan();

        verify(transactions).fail(work, "MEMORY_DISTILLATION_MIXED_SCOPE",
                "模型返回的记忆候选未通过安全校验，请重试");
        verify(transactions, never()).succeed(work, List.of());
    }

    @Test
    void incompletePerMessageAssessmentDoesNotAdvanceTheJob() {
        MemoryDistillationTransactions.Work work = work();
        when(transactions.claim()).thenReturn(work);
        when(conversations.load(42L, 0L, 5L)).thenReturn(List.of());
        when(extractor.extract(42L, 9L, List.of()))
                .thenThrow(new IllegalStateException("MEMORY_DISTILLATION_ASSESSMENT_INCOMPLETE"));

        worker(Runnable::run).scan();

        verify(transactions).fail(work, "MEMORY_DISTILLATION_ASSESSMENT_INCOMPLETE",
                "模型没有完成所有用户消息的记忆判断，请重试");
        verify(transactions, never()).succeed(work, List.of());
    }

    @Test
    void lowConfidenceRememberAssessmentDoesNotDisappearAsSuccessfulZeroResult() {
        MemoryDistillationTransactions.Work work = work();
        when(transactions.claim()).thenReturn(work);
        when(conversations.load(42L, 0L, 5L)).thenReturn(List.of());
        when(extractor.extract(42L, 9L, List.of()))
                .thenThrow(new IllegalStateException("MEMORY_DISTILLATION_CONFIDENCE_TOO_LOW"));

        worker(Runnable::run).scan();

        verify(transactions).fail(work, "MEMORY_DISTILLATION_CONFIDENCE_TOO_LOW",
                "模型识别到长期记忆，但判断置信度不足，请重试");
        verify(transactions, never()).succeed(work, List.of());
    }

    @Test
    void unresolvedScopeDoesNotAdvanceTheJob() {
        MemoryDistillationTransactions.Work work = work();
        when(transactions.claim()).thenReturn(work);
        when(conversations.load(42L, 0L, 5L)).thenReturn(List.of());
        when(extractor.extract(42L, 9L, List.of()))
                .thenThrow(new IllegalStateException("MEMORY_DISTILLATION_SCOPE_UNRESOLVED"));

        worker(Runnable::run).scan();

        verify(transactions).fail(work, "MEMORY_DISTILLATION_SCOPE_UNRESOLVED",
                "模型识别到长期记忆，但无法确定其全局或项目作用域，请重试");
        verify(transactions, never()).succeed(work, List.of());
    }

    @Test
    void rejectedDispatchMarksClaimedJobFailedAndReleasesWorker() {
        MemoryDistillationTransactions.Work work = work();
        when(transactions.claim()).thenReturn(work);
        TaskExecutor rejecting = task -> {
            throw new IllegalStateException("executor unavailable");
        };

        MemoryDistillationWorker worker = worker(rejecting);
        worker.scan();
        worker.scan();

        verify(transactions, org.mockito.Mockito.times(2)).claim();
        verify(transactions, org.mockito.Mockito.times(2)).fail(work,
                "MEMORY_DISTILLATION_WORKER_UNAVAILABLE", "长期记忆沉淀任务暂时不可用，请稍后重试");
        verify(extractor, never()).extract(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), anyList());
    }

    private MemoryDistillationWorker worker(TaskExecutor executor) {
        return new MemoryDistillationWorker(transactions, conversations, extractor, executor);
    }

    private MemoryDistillationTransactions.Work work() {
        return new MemoryDistillationTransactions.Work(9L, 42L, 0L, 5L);
    }

    private MemoryDistillationCandidate candidate() {
        return new MemoryDistillationCandidate(
                "USER", null, "PREFERENCE", "用户偏好简洁回答。", List.of("style"),
                new java.math.BigDecimal("0.90"), List.of(1L));
    }
}
