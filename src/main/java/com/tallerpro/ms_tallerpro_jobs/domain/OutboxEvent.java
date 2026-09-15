package com.tallerpro.ms_tallerpro_jobs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro del patron Outbox (T-02.08, ADR-07): se persiste en la misma transaccion que el
 * cambio de estado de la orden y es publicado de forma asincrona por OutboxPublisherService,
 * garantizando entrega hacia Kafka (jobs.events) o RabbitMQ (colas de comando) sin acoplar
 * el flujo transaccional a la disponibilidad del broker.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CanalEvento canal;

    /** Topico Kafka o routing key de RabbitMQ segun el canal. */
    @Column(nullable = false)
    private String destino;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Identificador idempotente del evento (eventId), propagado como header/metadata. */
    @Column(nullable = false)
    private String eventId;

    private String correlationId;
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EstadoOutbox estado = EstadoOutbox.PENDIENTE;

    @Builder.Default
    private Integer intentos = 0;

    @Column(nullable = false, updatable = false)
    private Instant fechaCreacion;

    private Instant fechaEnvio;

    @Column(columnDefinition = "TEXT")
    private String ultimoError;

    @PrePersist
    void alCrear() {
        this.fechaCreacion = Instant.now();
        if (this.estado == null) {
            this.estado = EstadoOutbox.PENDIENTE;
        }
        if (this.intentos == null) {
            this.intentos = 0;
        }
        if (this.eventId == null) {
            this.eventId = UUID.randomUUID().toString();
        }
    }
}
