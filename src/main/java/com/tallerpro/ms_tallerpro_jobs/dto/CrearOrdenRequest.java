package com.tallerpro.ms_tallerpro_jobs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** RF-04: creacion de orden. El taller (sucursal) es obligatorio por ser una plataforma multi-taller. */
public record CrearOrdenRequest(
        @NotNull(message = "El taller (sucursal) es obligatorio") UUID tallerId,
        @NotNull(message = "El cliente es obligatorio") UUID clienteId,
        @NotBlank String clienteNombre,
        String clienteContacto,
        @NotBlank String vehiculoPatente,
        String vehiculoMarca,
        String vehiculoModelo,
        Integer vehiculoAnio
) {}
