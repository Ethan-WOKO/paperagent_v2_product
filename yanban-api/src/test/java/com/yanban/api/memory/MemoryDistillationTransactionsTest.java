package com.yanban.api.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemoryDistillationTransactionsTest {
    @Mock
    private MemoryDistillationSettingRepository settings;

    @Mock
    private MemoryDistillationJobRepository jobs;

    @Mock
    private MemoryDistillationConversationService conversations;

    @Mock
    private LongTermMemoryService memories;

    @Mock
    private SysUserRepository users;

    private MemoryDistillationTransactions transactions;
    private MemoryDistillationSettingEntity setting;

    @Test
    void oneRequestDrainsMultipleBatchesAndRetainsCumulativeCounts() {
        var job = new MemoryDistillationJobEntity(42L, "MANUAL", 0L, 40L, 40, true, Instant.now());
        ReflectionTestUtils.setField(job, "id", 9L);
        when(jobs.findClaimable(any(), any())).thenReturn(List.of(job));
        when(jobs.findLocked(9L)).thenReturn(Optional.of(job));
        when(settings.findLocked(42L)).thenReturn(Optional.of(setting));
        when(users.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(new SysUser("active", "hash")));
        when(memories.createDistilledMemory(any(), any()))
                .thenReturn(new LongTermMemoryService.DistilledMemoryWriteResult(null, true));

        for (int batch = 0; batch < 4; batch++) {
            long from = batch * 10L;
            when(conversations.freezeBatch(42L, from, 40L))
                    .thenReturn(new MemoryDistillationConversationService.FrozenWindow(from, from + 10L, 10));
            var work = transactions.claim();
            assertThat(work.fromMessageId()).isEqualTo(from);
            transactions.succeed(work, batch == 3 ? List.of(candidate()) : List.of());
            assertThat(job.processedMessageCount()).isEqualTo((batch + 1) * 10);
            assertThat(setting.lastProcessedMessageId()).isEqualTo(from + 10L);
            assertThat(job.status()).isEqualTo(batch == 3 ? "SUCCEEDED" : "PENDING");
            if (batch < 3) {
                assertThat(job.finishedAt()).isNull();
                assertThat(setting.lastSuccessAt()).isNull();
            }
        }
        assertThat(job.createdMemoryCount()).isEqualTo(1);
        assertThat(job.candidateCount()).isEqualTo(1);
        assertThat(job.attemptCount()).isEqualTo(4); // Successful batches do not exhaust the retry limit.
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    void duplicateClickReturnsTheSameFrozenJob() {
        var job = runningJob(0, 5);
        when(settings.findLocked(42L)).thenReturn(Optional.of(setting));
        when(jobs.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(any(), any()))
                .thenReturn(Optional.of(job));
        assertThat(transactions.request(42L, "MANUAL")).isSameAs(job);
        verify(conversations, never()).freeze(any(Long.class), any(Long.class));
    }

    @Test
    void reclaimedBatchRejectsLateSuccessAndFailureFromPreviousAttempt() {
        var job = runningJob(0, 5);
        ReflectionTestUtils.setField(job, "claimedUntil", Instant.EPOCH);
        job.claim(Instant.now(), java.time.Duration.ofMinutes(3));
        when(jobs.findLocked(9L)).thenReturn(Optional.of(job));
        var stale = new MemoryDistillationTransactions.Work(9, 42, 0, 5, 2, 1);
        transactions.succeed(stale, List.of(candidate()));
        transactions.fail(stale, "MEMORY_DISTILLATION_MODEL_FAILED", "late failure");
        assertThat(job.status()).isEqualTo("RUNNING");
        assertThat(setting.lastProcessedMessageId()).isZero();
        verify(memories, never()).createDistilledMemory(any(), any());
        verify(settings, never()).findLocked(any());
    }

    @Test
    void failureAfterSuccessfulBatchPreservesCheckpointForTheNextRequest() {
        var job = new MemoryDistillationJobEntity(42, "MANUAL", 0, 20, 20, true, Instant.now());
        ReflectionTestUtils.setField(job, "id", 9L);
        when(jobs.findClaimable(any(), any())).thenReturn(List.of(job));
        when(jobs.findLocked(9L)).thenReturn(Optional.of(job));
        when(settings.findLocked(42L)).thenReturn(Optional.of(setting));
        when(users.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(new SysUser("active", "hash")));
        when(conversations.freezeBatch(42, 0, 20))
                .thenReturn(new MemoryDistillationConversationService.FrozenWindow(0, 10, 10));
        when(conversations.freezeBatch(42, 10, 20))
                .thenReturn(new MemoryDistillationConversationService.FrozenWindow(10, 20, 10));
        var first = transactions.claim();
        transactions.succeed(first, List.of());
        var second = transactions.claim();
        transactions.fail(second, "MEMORY_DISTILLATION_MODEL_FAILED", "failed");
        assertThat(setting.lastProcessedMessageId()).isEqualTo(10);
        assertThat(job.processedMessageCount()).isEqualTo(10);
        assertThat(job.status()).isEqualTo("FAILED");
        when(conversations.freeze(42, 10))
                .thenReturn(new MemoryDistillationConversationService.FrozenWindow(10, 20, 10));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var retry = transactions.request(42, "MANUAL");
        assertThat(retry.fromMessageId()).isEqualTo(10);
    }

    @BeforeEach
    void setUp() {
        setting = new MemoryDistillationSettingEntity(42L);
        transactions = new MemoryDistillationTransactions(
                settings, jobs, conversations, memories, new MemoryDistillationProperties(), users);
    }

    @Test
    void settingsAreOptInAndManualNoWorkDoesNotAdvanceCursor() {
        assertThat(setting.autoEnabled()).isFalse();
        assertThat(setting.lastProcessedMessageId()).isZero();
        when(settings.findLocked(42L)).thenReturn(Optional.of(setting));
        when(jobs.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(conversations.freeze(42L, 0L))
                .thenReturn(new MemoryDistillationConversationService.FrozenWindow(0L, 0L, 0));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MemoryDistillationJobEntity job = transactions.request(42L, MemoryDistillationJobEntity.TRIGGER_MANUAL);

        assertThat(job.status()).isEqualTo(MemoryDistillationJobEntity.STATUS_NO_WORK);
        assertThat(setting.lastProcessedMessageId()).isZero();
        verify(memories, never()).createDistilledMemory(any(), any());
    }

    @Test
    void successfulWriteAdvancesCursorOnlyAfterCandidateWrites() {
        MemoryDistillationJobEntity job = runningJob(0L, 5L);
        when(jobs.findLocked(9L)).thenReturn(Optional.of(job));
        when(users.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(new SysUser("active", "hash")));
        when(settings.findLocked(42L)).thenReturn(Optional.of(setting));
        when(memories.createDistilledMemory(any(), any()))
                .thenReturn(new LongTermMemoryService.DistilledMemoryWriteResult(null, true));
        MemoryDistillationCandidate candidate = candidate();

        transactions.succeed(new MemoryDistillationTransactions.Work(9L, 42L, 0L, 5L, 2, 1), List.of(candidate));

        assertThat(setting.lastProcessedMessageId()).isEqualTo(5L);
        assertThat(setting.lastSuccessAt()).isNotNull();
        assertThat(job.status()).isEqualTo(MemoryDistillationJobEntity.STATUS_SUCCEEDED);
        assertThat(job.createdMemoryCount()).isEqualTo(1);
    }

    @Test
    void failedCandidateWriteLeavesCursorUntouchedAndJobRunningForFailureHandler() {
        MemoryDistillationJobEntity job = runningJob(0L, 5L);
        when(jobs.findLocked(9L)).thenReturn(Optional.of(job));
        when(users.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(new SysUser("active", "hash")));
        when(settings.findLocked(42L)).thenReturn(Optional.of(setting));
        when(memories.createDistilledMemory(any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> transactions.succeed(
                new MemoryDistillationTransactions.Work(9L, 42L, 0L, 5L, 2, 1), List.of(candidate())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(setting.lastProcessedMessageId()).isZero();
        assertThat(setting.lastSuccessAt()).isNull();
        assertThat(job.status()).isEqualTo(MemoryDistillationJobEntity.STATUS_RUNNING);
        verify(settings, never()).save(setting);
    }

    @Test
    void accountDeletedDuringExtractionCannotPersistCandidatesOrAdvanceCursor() {
        MemoryDistillationJobEntity job = runningJob(0L, 5L);
        when(jobs.findLocked(9L)).thenReturn(Optional.of(job));
        when(users.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

        transactions.succeed(
                new MemoryDistillationTransactions.Work(9L, 42L, 0L, 5L, 2, 1), List.of(candidate()));

        assertThat(job.status()).isEqualTo(MemoryDistillationJobEntity.STATUS_FAILED);
        assertThat(job.errorCode()).isEqualTo("MEMORY_DISTILLATION_ACCOUNT_DELETED");
        assertThat(setting.lastProcessedMessageId()).isZero();
        verify(memories, never()).createDistilledMemory(any(), any());
        verify(settings, never()).save(setting);
    }

    private MemoryDistillationJobEntity runningJob(long from, long through) {
        MemoryDistillationJobEntity job = new MemoryDistillationJobEntity(
                42L, MemoryDistillationJobEntity.TRIGGER_MANUAL, from, through, 2, true, Instant.now());
        ReflectionTestUtils.setField(job, "id", 9L);
        job.claim(Instant.now(), java.time.Duration.ofMinutes(3));
        return job;
    }

    private MemoryDistillationCandidate candidate() {
        return new MemoryDistillationCandidate(
                "USER", null, "PREFERENCE", "用户偏好简洁回答。", List.of("style"),
                new BigDecimal("0.90"), List.of(1L));
    }
}
