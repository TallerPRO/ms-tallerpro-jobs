package com.tallerpro.ms_tallerpro_jobs.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** RF-06: asignacion de mecanico y bahia disponible a una orden activa. */
public record AsignarRecursosRequest(
        @NotNull UUID mecanicoId,
        String mecanicoNombre,
        /**
         * Correo del mecanico, para que notify le avise en que bahia tiene
         * trabajo. Lo entrega el catalogo de mecanicos del taller.
         */
        String mecanicoContacto,
        @NotNull UUID bahiaId
) {}
