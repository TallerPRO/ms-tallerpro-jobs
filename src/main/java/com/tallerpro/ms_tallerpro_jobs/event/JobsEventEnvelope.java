package com.tallerpro.ms_tallerpro_jobs.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope estandarizado para los eventos publicados en jobs.events (T-02.07),
 * segun lo definido en el ADR-06 (type, eventId, timestamp, traceId, correlationId).
 */
public record JobsEventEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        UUID aggregateId,
        Object payload
) {}
