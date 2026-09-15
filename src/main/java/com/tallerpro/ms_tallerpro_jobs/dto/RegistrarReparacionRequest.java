package com.tallerpro.ms_tallerpro_jobs.dto;

import java.util.List;
import java.util.UUID;

/** RF-05 (DIAGNOSTICADA -> EN_REPARACION). */
public record RegistrarReparacionRequest(
        String observaciones,
        List<RepuestoUsado> repuestosAdicionales
) {
    public record RepuestoUsado(UUID repuestoId, String nombre, Integer cantidad) {}
}
