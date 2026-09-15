package com.tallerpro.ms_tallerpro_jobs.domain;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepuestoUtilizado {
    private UUID repuestoId;
    private String nombre;
    private Integer cantidad;
}
