package com.yanban.paper.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PaperAsyncConfig {

    @Bean(name = "paperTaskExecutor")
    public Executor paperTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("yanban-paper-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.initialize();
        return executor;
    }

    @Bean(name = "paperSectionPolishExecutor")
    public Executor paperSectionPolishExecutor(
            @Value("${yanban.paper.polish.concurrency:2}") int configuredConcurrency) {
        int concurrency = Math.max(1, Math.min(3, configuredConcurrency));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("yanban-paper-polish-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }

    @Bean(name = "literatureTaskExecutor")
    public Executor literatureTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("yanban-literature-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(50);
        executor.initialize();
        return executor;
    }
}
