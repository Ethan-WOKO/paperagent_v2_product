package com.yanban.api.memory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class MemoryDistillationSettingInitializer {
    private final MemoryDistillationSettingRepository settings;

    MemoryDistillationSettingInitializer(MemoryDistillationSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    MemoryDistillationSettingEntity create(long userId) {
        return settings.saveAndFlush(new MemoryDistillationSettingEntity(userId));
    }
}
