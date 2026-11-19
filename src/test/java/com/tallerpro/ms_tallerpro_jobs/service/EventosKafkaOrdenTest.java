package com.tallerpro.ms_tallerpro_jobs.service;

import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import com.tallerpro.ms_tallerpro_jobs.domain.CanalEvento;
import com.tallerpro.ms_tallerpro_jobs.domain.OrdenServicio;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import com.tallerpro.ms_tallerpro_jobs.dto.CrearOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.repository.OrdenServicioRepository;
import com.tallerpro.ms_tallerpro_jobs.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cada evento de dominio de la orden se encola en el outbox para los DOS topicos Kafka
 * (jobs.events y audit.timeline), con el mismo eventId y la misma clave (id de la orden),
 * que el topico compactado audit.timeline necesita.
 */
class EventosKafkaOrdenTest {

    @Test
    void crearOrdenPublicaEnJobsEventsYAuditTimeline() {
        OrdenServicioRepository ordenRepository = mock(OrdenServicioRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        when(ordenRepository.save(any(OrdenServicio.class))).thenAnswer(inv -> {
            OrdenServicio orden = inv.getArgument(0);
            if (orden.getId() == null) {
                orden.setId(UUID.randomUUID());
            }
            return orden;
        });

        JobsProperties properties = new JobsProperties(
                new JobsProperties.Kafka("jobs.events", 3, 1, 604_800_000L, "audit.timeline"),
                new JobsProperties.Rabbitmq("cmd.direct", "cmd.dead.dlx", "q.email", "q.bay", "q.quote"),
                null, null, null);
        OrdenServicioServiceImpl service = new OrdenServicioServiceImpl(ordenRepository, outboxRepository,
                mock(CatalogClient.class), mock(EstadoOrdenValidator.class), properties, JsonMapper.builder().build());

        service.crearOrden(new CrearOrdenRequest(UUID.randomUUID(), UUID.randomUUID(), "Ana", null, null,
                "AB-CD-12", null, null, null, null, null, null, null));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, atLeastOnce()).save(captor.capture());
        List<OutboxEvent> kafka = captor.getAllValues().stream()
                .filter(e -> e.getCanal() == CanalEvento.KAFKA)
                .toList();

        assertThat(kafka).extracting(OutboxEvent::getDestino)
                .containsExactlyInAnyOrder("jobs.events", "audit.timeline");
        assertThat(kafka).extracting(OutboxEvent::getEventId).containsOnly(kafka.get(0).getEventId());
        assertThat(kafka).extracting(OutboxEvent::getAggregateId).doesNotContainNull()
                .containsOnly(kafka.get(0).getAggregateId());
        assertThat(kafka.get(0).getPayload()).isEqualTo(kafka.get(1).getPayload());
    }
}
