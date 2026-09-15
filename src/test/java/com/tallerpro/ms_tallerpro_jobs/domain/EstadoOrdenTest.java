package com.tallerpro.ms_tallerpro_jobs.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** T-02.13: tests unitarios de la maquina de estados (T-02.03 / RF-05). */
class EstadoOrdenTest {

    @ParameterizedTest
    @CsvSource({
            "RECEPCIONADA, DIAGNOSTICADA, true",
            "RECEPCIONADA, ANULADA, true",
            "RECEPCIONADA, EN_REPARACION, false",
            "DIAGNOSTICADA, EN_REPARACION, true",
            "DIAGNOSTICADA, ANULADA, true",
            "DIAGNOSTICADA, LISTA_RETIRO, false",
            "EN_REPARACION, LISTA_RETIRO, true",
            "EN_REPARACION, ANULADA, true",
            "EN_REPARACION, RECEPCIONADA, false",
            "LISTA_RETIRO, ENTREGADA, true",
            "LISTA_RETIRO, ANULADA, true",
            "LISTA_RETIRO, DIAGNOSTICADA, false",
            "ENTREGADA, ANULADA, false",
            "ANULADA, DIAGNOSTICADA, false"
    })
    void validaTransicionesSegunMatriz(EstadoOrden origen, EstadoOrden destino, boolean esperado) {
        assertThat(origen.puedeTransicionarA(destino)).isEqualTo(esperado);
    }

    @Test
    void noPermiteSaltarDiagnosticoParaPasarAEnReparacion() {
        assertThat(EstadoOrden.RECEPCIONADA.puedeTransicionarA(EstadoOrden.EN_REPARACION)).isFalse();
    }

    @Test
    void losEstadosFinalesNoTienenTransicionesValidas() {
        assertThat(EstadoOrden.ENTREGADA.esEstadoFinal()).isTrue();
        assertThat(EstadoOrden.ANULADA.esEstadoFinal()).isTrue();
        for (EstadoOrden destino : EstadoOrden.values()) {
            assertThat(EstadoOrden.ENTREGADA.puedeTransicionarA(destino)).isFalse();
            assertThat(EstadoOrden.ANULADA.puedeTransicionarA(destino)).isFalse();
        }
    }
}
