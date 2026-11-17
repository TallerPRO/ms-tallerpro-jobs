package com.tallerpro.ms_tallerpro_jobs.repository;

import com.tallerpro.ms_tallerpro_jobs.domain.CanalEvento;
import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOutbox;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifica la consulta findPublicables (backoff + orden por agregado) contra una BD real (H2). */
@DataJpaTest
class OutboxEventRepositoryTest {

    @Autowired
    private OutboxEventRepository repository;

    @Test
    void excluyeEventosEnBackoffYLosPosterioresDeLaMismaOrden() throws InterruptedException {
        Instant ahora = Instant.now();
        UUID ordenA = UUID.randomUUID();
        UUID ordenB = UUID.randomUUID();

        // Orden A: el primer evento espera su reintento -> el segundo NO debe adelantarse.
        OutboxEvent a1 = guardar(ordenA, "jobs.events", EstadoOutbox.PENDIENTE, ahora.plusSeconds(60));
        OutboxEvent a2 = guardar(ordenA, "jobs.events", EstadoOutbox.PENDIENTE, null);
        // Misma orden A pero otro destino (comando RabbitMQ): no comparte orden con jobs.events.
        OutboxEvent aEmail = guardar(ordenA, "q.cmd.email", EstadoOutbox.PENDIENTE, null);
        // Orden B: un FALLIDO anterior no bloquea; su backoff vencido si es publicable.
        guardar(ordenB, "jobs.events", EstadoOutbox.FALLIDO, null);
        OutboxEvent b2 = guardar(ordenB, "jobs.events", EstadoOutbox.PENDIENTE, ahora.minusSeconds(1));
        guardar(ordenB, "jobs.events", EstadoOutbox.ENVIADO, null);

        List<OutboxEvent> publicables = repository.findPublicables(ahora, PageRequest.of(0, 50));

        assertThat(publicables).extracting(OutboxEvent::getId)
                .containsExactly(aEmail.getId(), b2.getId())
                .doesNotContain(a1.getId(), a2.getId());
    }

    private OutboxEvent guardar(UUID orden, String destino, EstadoOutbox estado, Instant proximoIntento)
            throws InterruptedException {
        OutboxEvent evento = OutboxEvent.builder()
                .aggregateType("OrdenServicio")
                .aggregateId(orden)
                .eventType("ORDEN_ACTUALIZADA")
                .canal(destino.startsWith("q.") ? CanalEvento.RABBITMQ : CanalEvento.KAFKA)
                .destino(destino)
                .payload("{}")
                .estado(estado)
                .proximoIntento(proximoIntento)
                .build();
        OutboxEvent guardado = repository.saveAndFlush(evento);
        Thread.sleep(5); // fechaCreacion la fija @PrePersist: garantiza un orden estricto entre eventos
        return guardado;
    }
}
