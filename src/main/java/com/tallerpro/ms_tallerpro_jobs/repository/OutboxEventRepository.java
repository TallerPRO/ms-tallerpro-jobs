package com.tallerpro.ms_tallerpro_jobs.repository;

import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Eventos PENDIENTES listos para publicarse:
     *  - su backoff ya vencio (proximoIntento nulo o <= ahora), y
     *  - no hay un evento PENDIENTE anterior del mismo agregado hacia el mismo destino.
     * La segunda condicion conserva el orden por orden de servicio en jobs.events mientras un
     * evento anterior espera su reintento; los FALLIDO no bloquean (ya requieren intervencion).
     */
    @Query("""
            select e from OutboxEvent e
            where e.estado = com.tallerpro.ms_tallerpro_jobs.domain.EstadoOutbox.PENDIENTE
              and (e.proximoIntento is null or e.proximoIntento <= :ahora)
              and not exists (
                  select p.id from OutboxEvent p
                  where p.aggregateId = e.aggregateId
                    and p.destino = e.destino
                    and p.estado = com.tallerpro.ms_tallerpro_jobs.domain.EstadoOutbox.PENDIENTE
                    and p.fechaCreacion < e.fechaCreacion)
            order by e.fechaCreacion asc
            """)
    List<OutboxEvent> findPublicables(@Param("ahora") Instant ahora, Pageable pageable);
}
