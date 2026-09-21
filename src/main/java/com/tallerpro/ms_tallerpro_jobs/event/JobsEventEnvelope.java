package com.tallerpro.ms_tallerpro_jobs.event;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope estandarizado para los eventos publicados en jobs.events (T-02.07),
 * segun lo definido en el ADR-06 (type, eventId, timestamp, traceId, correlationId).
 *
 * Los campos planos orderId / tallerId / status / actor* son el contrato que consumen
 * ms-tallerpro-report (KPIs) y ms-tallerpro-audit (timeline: quien hizo que y cuando).
 * payload lleva la orden completa serializada como JSON, para trazabilidad.
 */
public record JobsEventEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        UUID aggregateId,
        String orderId,
        String tallerId,
        EstadoOrden status,
        String actorId,
        String actorName,
        String actorRole,
        String payload
) {}
