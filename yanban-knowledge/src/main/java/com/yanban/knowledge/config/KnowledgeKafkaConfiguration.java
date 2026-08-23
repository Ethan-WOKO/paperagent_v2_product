package com.yanban.knowledge.config;

import com.yanban.knowledge.service.KnowledgePermanentProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KnowledgeKafkaConfiguration {
    @Bean
    NewTopic knowledgeProcessingTopic(KnowledgeUploadProperties properties) {
        return TopicBuilder.name(properties.getProcessingTopic())
                .partitions(properties.getProcessingPartitions()).replicas(1).build();
    }

    @Bean
    NewTopic knowledgeProcessingDeadLetterTopic(KnowledgeUploadProperties properties) {
        return TopicBuilder.name(properties.getProcessingTopic() + ".DLT")
                .partitions(properties.getProcessingPartitions()).replicas(1).build();
    }

    @Bean(name = "knowledgeKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> knowledgeKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            KnowledgeUploadProperties properties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, error) -> new org.apache.kafka.common.TopicPartition(
                        properties.getProcessingTopic() + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(properties.getRetryBackoffMillis(), properties.getMaxProcessingAttempts() - 1L));
        handler.addNotRetryableExceptions(KnowledgePermanentProcessingException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);
        factory.setCommonErrorHandler(handler);
        factory.setConcurrency(Math.min(properties.getConsumerConcurrency(), properties.getProcessingPartitions()));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
