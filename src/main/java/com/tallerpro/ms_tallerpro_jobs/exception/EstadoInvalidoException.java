package com.tallerpro.ms_tallerpro_jobs.exception;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;

public class EstadoInvalidoException extends RuntimeException {
    public EstadoInvalidoException(EstadoOrden actual, EstadoOrden destino) {
        super("Transicion invalida: %s -> %s".formatted(actual, destino));
    }

    public EstadoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
