package com.tallerpro.ms_tallerpro_jobs.dto;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.domain.OrdenServicio;
import com.tallerpro.ms_tallerpro_jobs.domain.RepuestoUtilizado;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrdenServicioResponse(
        UUID id,
        UUID tallerId,
        UUID clienteId,
        String clienteNombre,
        String clienteContacto,
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
                o.getId(), o.getTallerId(), o.getClienteId(), o.getClienteNombre(), o.getClienteContacto(),
                o.getVehiculoPatente(), o.getVehiculoMarca(), o.getVehiculoModelo(), o.getVehiculoAnio(),
                o.getEstado(), o.getDiagnostico(), o.getMecanicoId(), o.getMecanicoNombre(), o.getBahiaId(),
                List.copyOf(o.getRepuestosUtilizados()), o.getMotivoAnulacion(), o.getFechaCreacion(), o.getFechaActualizacion(),
                o.getFechaDiagnostico(), o.getFechaEnReparacion(), o.getFechaListaRetiro(), o.getFechaEntrega(), o.getFechaAnulacion()
        );
    }
}
