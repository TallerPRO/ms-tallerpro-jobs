package com.tallerpro.ms_tallerpro_jobs.domain;

/** Estado de publicacion de un registro del patron Outbox (T-02.08). */
public enum EstadoOutbox {
    PENDIENTE,
    ENVIADO,
    FALLIDO
}
