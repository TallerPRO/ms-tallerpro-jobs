package com.tallerpro.ms_tallerpro_jobs.domain;

import com.tallerpro.ms_tallerpro_jobs.exception.EstadoInvalidoException;
import com.tallerpro.ms_tallerpro_jobs.service.EstadoOrdenValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstadoOrdenValidatorTest {

    private final EstadoOrdenValidator validator = new EstadoOrdenValidator();

    @Test
    void permiteTransicionValida() {
        assertThatCode(() -> validator.validarTransicion(EstadoOrden.RECEPCIONADA, EstadoOrden.DIAGNOSTICADA))
                .doesNotThrowAnyException();
    }

    @Test
    void rechazaTransicionInvalidaConEstadoInvalidoException() {
        assertThatThrownBy(() -> validator.validarTransicion(EstadoOrden.RECEPCIONADA, EstadoOrden.EN_REPARACION))
                .isInstanceOf(EstadoInvalidoException.class)
                .hasMessageContaining("RECEPCIONADA")
                .hasMessageContaining("EN_REPARACION");
    }
}
