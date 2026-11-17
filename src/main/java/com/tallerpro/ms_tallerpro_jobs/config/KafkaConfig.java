package com.tallerpro.ms_tallerpro_jobs.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * T-02.07: topico jobs.events para eventos de dominio consumidos por audit y report.
 *
 * Retencion explicita (guia 3.3.1): cleanup.policy=delete por tiempo. Es la ventana en la que un
 * consumidor puede reprocesar el historial (reset de offsets a EARLIEST desde el admin de Kafka).
 * Con spring.kafka.admin.modify-topic-configs=true se aplica tambien si el topico ya existia.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic jobsEventsTopic(JobsProperties properties) {
        return TopicBuilder.name(properties.kafka().topicJobsEvents())
                .partitions(properties.kafka().partitions())
                .replicas(properties.kafka().replicas())
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(properties.kafka().retentionMs()))
                .build();
    }
}
