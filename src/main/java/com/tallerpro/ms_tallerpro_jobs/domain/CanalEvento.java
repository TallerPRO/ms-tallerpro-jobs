package com.tallerpro.ms_tallerpro_jobs.domain;

/** Canal de entrega de un evento del patron Outbox (T-02.08). */
public enum CanalEvento {
    KAFKA,
    RABBITMQ
}
