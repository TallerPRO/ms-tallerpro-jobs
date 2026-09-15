package com.tallerpro.ms_tallerpro_jobs.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados del ciclo de vida de una Orden de Servicio (RF-05, T-02.03).
 * Maquina de estados: RECEPCIONADA -> DIAGNOSTICADA -> EN_REPARACION -> LISTA_RETIRO -> ENTREGADA / ANULADA
 */
public enum EstadoOrden {
    RECEPCIONADA,
    DIAGNOSTICADA,
    EN_REPARACION,
    LISTA_RETIRO,
    ENTREGADA,
    ANULADA;

    private static final Map<EstadoOrden, Set<EstadoOrden>> TRANSICIONES_VALIDAS = Map.of(
            RECEPCIONADA, EnumSet.of(DIAGNOSTICADA, ANULADA),
            DIAGNOSTICADA, EnumSet.of(EN_REPARACION, ANULADA),
            EN_REPARACION, EnumSet.of(LISTA_RETIRO, ANULADA),
            LISTA_RETIRO, EnumSet.of(ENTREGADA, ANULADA),
            ENTREGADA, EnumSet.noneOf(EstadoOrden.class),
            ANULADA, EnumSet.noneOf(EstadoOrden.class)
    );

    public boolean puedeTransicionarA(EstadoOrden destino) {
        return TRANSICIONES_VALIDAS.getOrDefault(this, Set.of()).contains(destino);
    }

    public boolean esEstadoFinal() {
        return this == ENTREGADA || this == ANULADA;
    }
}
