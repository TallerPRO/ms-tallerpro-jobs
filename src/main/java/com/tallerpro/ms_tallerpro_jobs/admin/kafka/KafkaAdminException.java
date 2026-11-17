package com.tallerpro.ms_tallerpro_jobs.admin.kafka;

import org.springframework.http.HttpStatus;

/** Error del administrador de Kafka ya traducido a un codigo HTTP (lo resuelve GlobalExceptionHandler). */
public class KafkaAdminException extends RuntimeException {

    private final HttpStatus status;

    public KafkaAdminException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public KafkaAdminException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
