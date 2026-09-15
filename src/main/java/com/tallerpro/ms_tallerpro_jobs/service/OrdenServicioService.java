package com.tallerpro.ms_tallerpro_jobs.service;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.dto.AnularOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.AsignarRecursosRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.CrearOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.DiagnosticarOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.OrdenServicioResponse;
import com.tallerpro.ms_tallerpro_jobs.dto.RegistrarReparacionRequest;

import java.util.List;
import java.util.UUID;

public interface OrdenServicioService {

    OrdenServicioResponse crearOrden(CrearOrdenRequest request);

    OrdenServicioResponse obtenerOrden(UUID id);

    List<OrdenServicioResponse> listarOrdenes(UUID tallerId, EstadoOrden estado);

    OrdenServicioResponse consultarEstadoParaCliente(UUID id, UUID clienteId);

    OrdenServicioResponse asignarRecursos(UUID id, AsignarRecursosRequest request);

    OrdenServicioResponse diagnosticar(UUID id, DiagnosticarOrdenRequest request);

    OrdenServicioResponse registrarReparacion(UUID id, RegistrarReparacionRequest request);

    OrdenServicioResponse marcarListaParaRetiro(UUID id);

    OrdenServicioResponse entregar(UUID id);

    OrdenServicioResponse anular(UUID id, AnularOrdenRequest request);
}
