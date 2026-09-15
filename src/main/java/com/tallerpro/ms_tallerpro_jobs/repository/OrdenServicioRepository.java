package com.tallerpro.ms_tallerpro_jobs.repository;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.domain.OrdenServicio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository Pattern (T-02.01) sobre Spring Data JPA para el agregado OrdenServicio. */
public interface OrdenServicioRepository extends JpaRepository<OrdenServicio, UUID> {

    List<OrdenServicio> findByTallerId(UUID tallerId);

    List<OrdenServicio> findByEstado(EstadoOrden estado);

    List<OrdenServicio> findByTallerIdAndEstado(UUID tallerId, EstadoOrden estado);

    Optional<OrdenServicio> findByIdAndClienteId(UUID id, UUID clienteId);
}
