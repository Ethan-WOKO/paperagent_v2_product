package com.yanban.api.memory;

import com.yanban.api.user.SysUserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class MemoryDistillationTransactions {
    private static final List<String> ACTIVE = List.of(
            MemoryDistillationJobEntity.STATUS_PENDING, MemoryDistillationJobEntity.STATUS_RUNNING);

    private final MemoryDistillationSettingRepository settings;
    private final MemoryDistillationJobRepository jobs;
    private final MemoryDistillationConversationService conversations;
    private final LongTermMemoryService memories;
    private final MemoryDistillationProperties properties;
    private final SysUserRepository users;

    MemoryDistillationTransactions(MemoryDistillationSettingRepository settings,
                                   MemoryDistillationJobRepository jobs,
                                   MemoryDistillationConversationService conversations,
                                   LongTermMemoryService memories,
                                   MemoryDistillationProperties properties,
                                   SysUserRepository users) {
        this.settings = settings;
        this.jobs = jobs;
        this.conversations = conversations;
        this.memories = memories;
        this.properties = properties;
        this.users = users;
    }

    @Transactional
    MemoryDistillationSettingEntity updateAuto(long userId, boolean enabled) {
        Instant now = Instant.now();
        MemoryDistillationSettingEntity setting = lockedSetting(userId);
        setting.updateAutoEnabled(enabled, now, properties.getInterval());
        return settings.saveAndFlush(setting);
    }

    @Transactional
    MemoryDistillationJobEntity request(long userId, String triggerType) {
        MemoryDistillationSettingEntity setting = lockedSetting(userId);
        var active = jobs.findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(userId, ACTIVE);
        if (active.isPresent()) return active.get();
        Instant now = Instant.now();
        MemoryDistillationConversationService.FrozenWindow window = conversations.freeze(
                userId, setting.lastProcessedMessageId());
        MemoryDistillationJobEntity job = new MemoryDistillationJobEntity(
                userId, triggerType, window.fromMessageId(), window.throughMessageId(),
                window.messageCount(), window.hasWork(), now);
        if (MemoryDistillationJobEntity.TRIGGER_AUTO.equals(triggerType)) {
            setting.scheduleNext(now, properties.getInterval());
            settings.save(setting);
        }
        return jobs.saveAndFlush(job);
    }

    @Transactional(readOnly = true)
    List<Long> dueUsers() {
        return settings.findDue(Instant.now(), PageRequest.of(0, 20)).stream()
                .map(MemoryDistillationSettingEntity::userId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Work claim() {
        Instant now = Instant.now();
        List<MemoryDistillationJobEntity> values = jobs.findClaimable(now, PageRequest.of(0, 1));
        if (values.isEmpty()) return null;
        MemoryDistillationJobEntity job = values.get(0);
        if (job.attemptCount() >= properties.getMaxAttempts()) {
            job.fail("MEMORY_DISTILLATION_ATTEMPTS_EXHAUSTED", "多次处理失败，请手动重新发起", now);
            jobs.saveAndFlush(job);
            return null;
        }
        job.claim(now, properties.getClaimLease());
        jobs.saveAndFlush(job);
        return new Work(job.id(), job.userId(), job.fromMessageId(), job.throughMessageId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void succeed(Work work, List<MemoryDistillationCandidate> candidates) {
        MemoryDistillationJobEntity job = jobs.findLocked(work.jobId()).orElseThrow();
        if (!MemoryDistillationJobEntity.STATUS_RUNNING.equals(job.status())) return;
        if (users.findByIdAndDeletedAtIsNull(work.userId()).isEmpty()) {
            job.fail("MEMORY_DISTILLATION_ACCOUNT_DELETED", "账号已删除，沉淀任务已停止", Instant.now());
            jobs.saveAndFlush(job);
            return;
        }
        MemoryDistillationSettingEntity setting = lockedSetting(work.userId());
        int created = 0;
        for (MemoryDistillationCandidate candidate : candidates) {
            if (memories.createDistilledMemory(work.userId(), candidate).created()) created++;
        }
        Instant now = Instant.now();
        setting.advance(work.fromMessageId(), work.throughMessageId(), now, properties.getInterval());
        job.succeed(candidates.size(), created, now);
        settings.save(setting);
        jobs.saveAndFlush(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(Work work, String code, String message) {
        jobs.findLocked(work.jobId()).ifPresent(job -> {
            job.fail(code, message, Instant.now());
            jobs.saveAndFlush(job);
        });
    }

    private MemoryDistillationSettingEntity lockedSetting(long userId) {
        return settings.findLocked(userId)
                .orElseThrow(() -> new IllegalStateException("MEMORY_DISTILLATION_SETTINGS_MISSING"));
    }

    record Work(long jobId, long userId, long fromMessageId, long throughMessageId) { }
}
