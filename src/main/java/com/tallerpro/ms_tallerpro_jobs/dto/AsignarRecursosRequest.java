package com.tallerpro.ms_tallerpro_jobs.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** RF-06: asignacion de mecanico y bahia disponible a una orden activa. */
public record AsignarRecursosRequest(
        @NotNull UUID mecanicoId,
        String mecanicoNombre,
        @NotNull UUID bahiaId
) {}
