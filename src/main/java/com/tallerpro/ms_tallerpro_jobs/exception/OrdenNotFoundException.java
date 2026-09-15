package com.tallerpro.ms_tallerpro_jobs.exception;

import java.util.UUID;

public class OrdenNotFoundException extends RuntimeException {
    public OrdenNotFoundException(UUID id) {
        super("Orden de servicio no encontrada: " + id);
    }
}
