package com.tallerpro.ms_tallerpro_jobs.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/** RF-05 (RECEPCIONADA -> DIAGNOSTICADA). Los repuestos requeridos disparan RF-08 (T-02.09). */
public record DiagnosticarOrdenRequest(
        @NotBlank String diagnostico,
        List<RepuestoRequerido> repuestos
) {
    public record RepuestoRequerido(UUID repuestoId, String nombre, Integer cantidad) {}
}
