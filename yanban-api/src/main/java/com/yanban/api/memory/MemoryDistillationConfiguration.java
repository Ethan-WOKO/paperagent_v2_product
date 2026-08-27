package com.yanban.api.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(MemoryDistillationProperties.class)
class MemoryDistillationConfiguration {
    @Bean(name = "memoryDistillationExecutor")
    @ConditionalOnProperty(prefix = "yanban.memory.distillation", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskExecutor memoryDistillationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("memory-distillation-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
