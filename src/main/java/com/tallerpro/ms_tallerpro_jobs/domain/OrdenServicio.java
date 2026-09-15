package com.tallerpro.ms_tallerpro_jobs.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orden de Servicio (MS-01 / EP-02). Nucleo transaccional del sistema (RF-04, RF-05, RF-06).
 * El identificador es UUID para evitar enumeracion maliciosa (T-02.11 / RNF Seguridad).
 */
@Entity
@Table(name = "ordenes_servicio")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrdenServicio {

    @Id
    @GeneratedValue
    private UUID id;

    /** Sucursal/taller propietario de la orden. Campo obligatorio (multi-taller, 20 talleres de la red). */
    @Column(nullable = false, updatable = false)
    private UUID tallerId;

    @Column(nullable = false)
    private UUID clienteId;

    @Column(nullable = false)
    private String clienteNombre;

    private String clienteContacto;

    @Column(nullable = false)
    private String vehiculoPatente;

    private String vehiculoMarca;
    private String vehiculoModelo;
    private Integer vehiculoAnio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EstadoOrden estado = EstadoOrden.RECEPCIONADA;

    @Column(columnDefinition = "TEXT")
    private String diagnostico;

    private UUID mecanicoId;
    private String mecanicoNombre;
    private UUID bahiaId;

    @ElementCollection
    @CollectionTable(name = "orden_repuestos", joinColumns = @JoinColumn(name = "orden_id"))
    @Builder.Default
    private List<RepuestoUtilizado> repuestosUtilizados = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String motivoAnulacion;

    @Column(nullable = false, updatable = false)
    private Instant fechaCreacion;

    @Column(nullable = false)
    private Instant fechaActualizacion;

    private Instant fechaDiagnostico;
    private Instant fechaEnReparacion;
    private Instant fechaListaRetiro;
    private Instant fechaEntrega;
    private Instant fechaAnulacion;

    @Version
    private Long version;

    @PrePersist
    void alCrear() {
        Instant ahora = Instant.now();
        this.fechaCreacion = ahora;
        this.fechaActualizacion = ahora;
        if (this.estado == null) {
            this.estado = EstadoOrden.RECEPCIONADA;
        }
        if (this.repuestosUtilizados == null) {
            this.repuestosUtilizados = new ArrayList<>();
        }
    }

    @PreUpdate
    void alActualizar() {
        this.fechaActualizacion = Instant.now();
    }
}
