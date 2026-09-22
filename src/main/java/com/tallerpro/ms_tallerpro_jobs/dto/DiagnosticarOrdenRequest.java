package com.tallerpro.ms_tallerpro_jobs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

/**
 * RF-05 (RECEPCIONADA -> DIAGNOSTICADA). Ademas del diagnostico, aqui se
 * registran los repuestos y servicios que lleva el trabajo: el diagnostico es
 * el momento en que se sabe que se va a cobrar.
 *
 * Solo viajan ids y cantidades: los precios los resuelve jobs contra
 * ms-tallerpro-catalog, para que el total no dependa de lo que envie el cliente.
 * Los repuestos ademas descuentan stock (RF-08, T-02.09).
 */
public record DiagnosticarOrdenRequest(
        @NotBlank String diagnostico,
        List<RepuestoRequerido> repuestos,
        List<ServicioRequerido> servicios
) {
    /**
     * `nombre` queda por compatibilidad con el contrato anterior; si catalog
     * responde, manda el nombre del catalogo.
     */
    public record RepuestoRequerido(@NotNull UUID repuestoId, String nombre, @NotNull @Positive Integer cantidad) {}

    public record ServicioRequerido(@NotNull UUID servicioId, @NotNull @Positive Integer cantidad) {}
}
