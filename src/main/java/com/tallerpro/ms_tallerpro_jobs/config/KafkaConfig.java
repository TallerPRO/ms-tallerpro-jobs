package com.tallerpro.ms_tallerpro_jobs.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** T-02.07: topico jobs.events para eventos de dominio consumidos por audit y report. */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic jobsEventsTopic(JobsProperties properties) {
        return TopicBuilder.name(properties.kafka().topicJobsEvents())
                .partitions(properties.kafka().partitions())
                .replicas(properties.kafka().replicas())
                .build();
    }
}
