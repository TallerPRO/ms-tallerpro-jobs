package com.tallerpro.ms_tallerpro_jobs.dto;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.domain.OrdenServicio;
import com.tallerpro.ms_tallerpro_jobs.domain.RepuestoUtilizado;
import com.tallerpro.ms_tallerpro_jobs.domain.ServicioAplicado;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrdenServicioResponse(
        UUID id,
        UUID tallerId,
        UUID clienteId,
        String clienteNombre,
        String clienteContacto,
        String clienteTelefono,
        String vehiculoPatente,
        String vehiculoMarca,
        String vehiculoModelo,
        Integer vehiculoAnio,
        EstadoOrden estado,
        String diagnostico,
        UUID mecanicoId,
        String mecanicoNombre,
        UUID bahiaId,
        List<RepuestoUtilizado> repuestosUtilizados,
        List<ServicioAplicado> serviciosAplicados,
        /** Monto a cobrar: repuestos + servicios con el precio que tenian al usarlos. */
        BigDecimal total,
        String motivoAnulacion,
        Instant fechaCreacion,
        Instant fechaActualizacion,
        Instant fechaDiagnostico,
        Instant fechaEnReparacion,
        Instant fechaListaRetiro,
        Instant fechaEntrega,
        Instant fechaAnulacion
) {
    public static OrdenServicioResponse from(OrdenServicio o) {
        return new OrdenServicioResponse(
                o.getId(), o.getTallerId(), o.getClienteId(), o.getClienteNombre(), o.getClienteContacto(), o.getClienteTelefono(),
                o.getVehiculoPatente(), o.getVehiculoMarca(), o.getVehiculoModelo(), o.getVehiculoAnio(),
                o.getEstado(), o.getDiagnostico(), o.getMecanicoId(), o.getMecanicoNombre(), o.getBahiaId(),
                List.copyOf(o.getRepuestosUtilizados()), List.copyOf(o.getServiciosAplicados()), o.getTotal(), o.getMotivoAnulacion(), o.getFechaCreacion(), o.getFechaActualizacion(),
                o.getFechaDiagnostico(), o.getFechaEnReparacion(), o.getFechaListaRetiro(), o.getFechaEntrega(), o.getFechaAnulacion()
        );
    }
}
