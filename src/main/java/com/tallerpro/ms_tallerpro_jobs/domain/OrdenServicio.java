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

import java.math.BigDecimal;
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

    /** Telefono del cliente, para contactarlo cuando el correo no alcanza. */
    private String clienteTelefono;

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

    /** Correo del mecanico: a el llega el aviso de trabajo cuando se ocupa la bahia. */
    private String mecanicoContacto;
    private UUID bahiaId;

    @ElementCollection
    @CollectionTable(name = "orden_repuestos", joinColumns = @JoinColumn(name = "orden_id"))
    @Builder.Default
    private List<RepuestoUtilizado> repuestosUtilizados = new ArrayList<>();

    /** Servicios del catalogo ejecutados sobre el vehiculo (mano de obra). */
    @ElementCollection
    @CollectionTable(name = "orden_servicios", joinColumns = @JoinColumn(name = "orden_id"))
    @Builder.Default
    private List<ServicioAplicado> serviciosAplicados = new ArrayList<>();

    /**
     * Monto a cobrar: suma de repuestos y servicios con el precio que tenian al
     * usarlos. Se recalcula en cada cambio de la orden, nunca lo envia el cliente.
     */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

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
        if (this.serviciosAplicados == null) {
            this.serviciosAplicados = new ArrayList<>();
        }
        if (this.total == null) {
            this.total = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    void alActualizar() {
        this.fechaActualizacion = Instant.now();
    }

    /**
     * Suma repuestos + servicios. Se llama desde el servicio cada vez que la
     * orden cambia de items, para que `total` nunca quede desalineado del detalle.
     */
    public void recalcularTotal() {
        BigDecimal suma = BigDecimal.ZERO;
        if (repuestosUtilizados != null) {
            for (RepuestoUtilizado r : repuestosUtilizados) {
                suma = suma.add(r.getSubtotal());
            }
        }
        if (serviciosAplicados != null) {
            for (ServicioAplicado s : serviciosAplicados) {
                suma = suma.add(s.getSubtotal());
            }
        }
        this.total = suma;
    }
}
