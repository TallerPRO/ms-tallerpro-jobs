package com.tallerpro.ms_tallerpro_jobs.service;

import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import com.tallerpro.ms_tallerpro_jobs.domain.CanalEvento;
import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOutbox;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import com.tallerpro.ms_tallerpro_jobs.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-17T12:00:00Z");

    private OutboxEventRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxPublisherService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        JobsProperties properties = new JobsProperties(
                new JobsProperties.Kafka("jobs.events", 3, 1, 604_800_000L, "audit.timeline"),
                new JobsProperties.Rabbitmq("cmd.direct", "cmd.dead.dlx", "q.email", "q.bay", "q.quote"),
                null,
                new JobsProperties.Outbox(true, 2000, 50, 3, 5000, 12000),
                null);
        service = new OutboxPublisherService(repository, kafkaTemplate, mock(RabbitTemplate.class), properties,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void publicacionExitosaMarcaEnviadoYLimpiaElBackoff() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxEvent evento = eventoKafka();
        evento.setIntentos(1);
        evento.setProximoIntento(AHORA.minusSeconds(1));
        evento.setUltimoError("timeout");

        service.publicar(evento);

        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.ENVIADO);
        assertThat(evento.getFechaEnvio()).isEqualTo(AHORA);
        assertThat(evento.getProximoIntento()).isNull();
        assertThat(evento.getUltimoError()).isNull();
        verify(repository).save(evento);
    }

    @Test
    void falloTransitorioQuedaPendienteConBackoffExponencial() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker caido")));
        OutboxEvent evento = eventoKafka();

        service.publicar(evento);
        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.PENDIENTE);
        assertThat(evento.getIntentos()).isEqualTo(1);
        assertThat(evento.getProximoIntento()).isEqualTo(AHORA.plusMillis(5000));
        assertThat(evento.getUltimoError()).contains("broker caido");

        service.publicar(evento);
        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.PENDIENTE);
        assertThat(evento.getIntentos()).isEqualTo(2);
        assertThat(evento.getProximoIntento()).isEqualTo(AHORA.plusMillis(10000));
    }

    @Test
    void alAgotarLosIntentosElEventoQuedaFallidoYNoSeReprograma() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("payload rechazado")));
        OutboxEvent evento = eventoKafka();
        evento.setIntentos(2); // maxIntentos = 3 -> este es el ultimo

        service.publicar(evento);

        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.FALLIDO);
        assertThat(evento.getIntentos()).isEqualTo(3);
        assertThat(evento.getProximoIntento()).isNull();
        assertThat(evento.getUltimoError()).contains("payload rechazado");
    }

    @Test
    void backoffSeDuplicaYRespetaElTope() {
        assertThat(service.backoff(1)).isEqualTo(Duration.ofMillis(5000));
        assertThat(service.backoff(2)).isEqualTo(Duration.ofMillis(10000));
        assertThat(service.backoff(3)).isEqualTo(Duration.ofMillis(12000)); // tope
        assertThat(service.backoff(64)).isEqualTo(Duration.ofMillis(12000)); // sin overflow
    }

    @Test
    void elPollingSoloPideEventosPublicablesSegunElReloj() {
        when(repository.findPublicables(eq(AHORA), any(Pageable.class))).thenReturn(List.of());

        service.publicarPendientes();

        verify(repository).findPublicables(eq(AHORA), any(Pageable.class));
    }

    private static OutboxEvent eventoKafka() {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("OrdenServicio")
                .aggregateId(UUID.randomUUID())
                .eventType("ORDEN_CREADA")
                .canal(CanalEvento.KAFKA)
                .destino("jobs.events")
                .payload("{}")
                .eventId(UUID.randomUUID().toString())
                .build();
    }
}
