package com.tallerpro.ms_tallerpro_jobs.dto;

import java.util.UUID;

/** Respuesta de ms-tallerpro-catalog al consultar disponibilidad de una bahia (T-02.04 / RF-06). */
public record BahiaDisponibilidadResponse(UUID bahiaId, UUID tallerId, boolean disponible) {}
