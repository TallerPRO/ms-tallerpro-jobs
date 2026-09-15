package com.tallerpro.ms_tallerpro_jobs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tallerpro.jobs")
public record JobsProperties(
        Kafka kafka,
        Rabbitmq rabbitmq,
        Catalog catalog,
        Outbox outbox
) {
    public record Kafka(String topicJobsEvents, int partitions, int replicas) {}

    public record Rabbitmq(String exchangeCmd, String exchangeDlx, String queueEmail, String queueBay, String queueQuote) {}

    public record Catalog(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {}

    public record Outbox(boolean publisherEnabled, long pollFixedDelayMs, int batchSize) {}
}
