package com.tallerpro.ms_tallerpro_jobs.dto;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message, String path) {}
