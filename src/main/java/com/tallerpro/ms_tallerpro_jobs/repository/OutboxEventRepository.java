package com.tallerpro.ms_tallerpro_jobs.repository;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOutbox;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByEstadoOrderByFechaCreacionAsc(EstadoOutbox estado, Pageable pageable);
}
