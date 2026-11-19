package com.tallerpro.ms_tallerpro_jobs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "tallerpro.jobs")
public record JobsProperties(
        Kafka kafka,
        Rabbitmq rabbitmq,
        Catalog catalog,
        Outbox outbox,
        Demo demo
) {
    /**
     * retentionMs: retencion de jobs.events (7 dias por defecto).
     * topicAuditTimeline: espejo compactado por orderId que consume audit; lo declara audit
     * (cleanup.policy=compact), jobs solo publica en el.
     */
    public record Kafka(String topicJobsEvents, int partitions, int replicas,
                        @DefaultValue("604800000") long retentionMs,
                        @DefaultValue("audit.timeline") String topicAuditTimeline) {}

    public record Rabbitmq(String exchangeCmd, String exchangeDlx, String queueEmail, String queueBay, String queueQuote) {}

    public record Catalog(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {}

    /**
     * Reintentos del publicador Outbox: backoff exponencial (backoffInicialMs, x2 en cada fallo,
     * tope backoffMaxMs) y, al agotar maxIntentos, el evento pasa a FALLIDO para no reintentarse
     * indefinidamente (evita la "tormenta de reintentos").
     */
    public record Outbox(boolean publisherEnabled,
                         long pollFixedDelayMs,
                         int batchSize,
                         @DefaultValue("10") int maxIntentos,
                         @DefaultValue("5000") long backoffInicialMs,
                         @DefaultValue("300000") long backoffMaxMs) {}

    /** Correos usados por DemoDataSeeder (perfil "demo"), ver T-02.13. */
    public record Demo(String clienteEmail, String mecanicoEmail) {}
}
