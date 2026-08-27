package com.yanban.api.memory;

import com.yanban.core.user.UserAccountPolicy;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemoryDistillationService {
    private final MemoryDistillationSettingRepository settings;
    private final MemoryDistillationJobRepository jobs;
    private final MemoryDistillationSettingInitializer initializer;
    private final MemoryDistillationTransactions transactions;
    private final MemoryDistillationProperties properties;
    private final UserAccountPolicy accountPolicy;

    MemoryDistillationService(MemoryDistillationSettingRepository settings,
                              MemoryDistillationJobRepository jobs,
                              MemoryDistillationSettingInitializer initializer,
                              MemoryDistillationTransactions transactions,
                              MemoryDistillationProperties properties,
                              UserAccountPolicy accountPolicy) {
        this.settings = settings;
        this.jobs = jobs;
        this.initializer = initializer;
        this.transactions = transactions;
        this.properties = properties;
        this.accountPolicy = accountPolicy;
    }

    public MemoryDistillationSettingsResponse getSettings(long userId) {
        Optional<MemoryDistillationSettingEntity> setting = settings.findById(userId);
        return response(setting.orElse(null), latest(userId).orElse(null));
    }

    public MemoryDistillationSettingsResponse updateSettings(long userId, boolean autoEnabled) {
        accountPolicy.assertSettingsMutable(userId);
        ensureSetting(userId);
        MemoryDistillationSettingEntity setting = transactions.updateAuto(userId, autoEnabled);
        return response(setting, latest(userId).orElse(null));
    }

    public MemoryDistillationJobResponse requestManual(long userId) {
        accountPolicy.assertSettingsMutable(userId);
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "memory distillation is unavailable");
        }
        ensureSetting(userId);
        return MemoryDistillationJobResponse.from(
                transactions.request(userId, MemoryDistillationJobEntity.TRIGGER_MANUAL));
    }

    public MemoryDistillationJobResponse getJob(long userId, long jobId) {
        return jobs.findByIdAndUserId(jobId, userId)
                .map(MemoryDistillationJobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "memory distillation job not found"));
    }

    void requestAutomatic(long userId) {
        if (!properties.isEnabled()) return;
        ensureSetting(userId);
        transactions.request(userId, MemoryDistillationJobEntity.TRIGGER_AUTO);
    }

    private void ensureSetting(long userId) {
        if (settings.existsById(userId)) return;
        try {
            initializer.create(userId);
        } catch (DataIntegrityViolationException raced) {
            if (!settings.existsById(userId)) throw raced;
        }
    }

    private Optional<MemoryDistillationJobEntity> latest(long userId) {
        return jobs.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    private MemoryDistillationSettingsResponse response(MemoryDistillationSettingEntity setting,
                                                        MemoryDistillationJobEntity latest) {
        return new MemoryDistillationSettingsResponse(
                properties.isEnabled(),
                setting != null && setting.autoEnabled(),
                properties.getInterval().toSeconds(),
                setting == null ? 0L : setting.lastProcessedMessageId(),
                setting == null ? null : setting.nextRunAt(),
                setting == null ? null : setting.lastSuccessAt(),
                latest == null ? null : MemoryDistillationJobResponse.from(latest));
    }
}
