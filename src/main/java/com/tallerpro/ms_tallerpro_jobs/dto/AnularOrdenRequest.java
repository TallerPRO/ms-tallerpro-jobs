package com.tallerpro.ms_tallerpro_jobs.dto;

import jakarta.validation.constraints.NotBlank;

public record AnularOrdenRequest(@NotBlank String motivo) {}
