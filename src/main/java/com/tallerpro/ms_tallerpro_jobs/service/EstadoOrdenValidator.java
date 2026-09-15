package com.tallerpro.ms_tallerpro_jobs.service;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.exception.EstadoInvalidoException;
import org.springframework.stereotype.Component;

/** Motor de la maquina de estados (T-02.03 / RF-05): valida cada transicion antes de aplicarla. */
@Component
public class EstadoOrdenValidator {

    public void validarTransicion(EstadoOrden actual, EstadoOrden destino) {
        if (!actual.puedeTransicionarA(destino)) {
            throw new EstadoInvalidoException(actual, destino);
        }
    }
}
