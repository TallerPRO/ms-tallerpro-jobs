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
        String clienteTelefono,
        @NotBlank String vehiculoPatente,
        String vehiculoMarca,
        String vehiculoModelo,
        Integer vehiculoAnio,
        /**
         * Bahia que se reserva para el vehiculo al recepcionarlo (RF-06).
         * Opcional en el contrato porque un Cliente tambien puede crear la orden
         * y no elige bahia; la recepcion en el taller si la exige.
         */
        UUID bahiaId,
        /**
         * Mecanico a cargo, elegido del catalogo de mecanicos del taller. Igual
         * que la bahia: opcional en el contrato, obligatorio en la recepcion.
         * El contacto se guarda para avisarle cuando el vehiculo entre a la bahia.
         */
        UUID mecanicoId,
        String mecanicoNombre,
        String mecanicoContacto
) {}
