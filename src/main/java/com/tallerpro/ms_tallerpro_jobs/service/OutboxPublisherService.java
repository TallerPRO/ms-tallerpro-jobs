package com.tallerpro.ms_tallerpro_jobs.service;

import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import com.tallerpro.ms_tallerpro_jobs.domain.CanalEvento;
import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOutbox;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import com.tallerpro.ms_tallerpro_jobs.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Publicador del patron Outbox (T-02.08, ADR-07): entrega de forma asincrona y con reintentos
 * idempotentes (eventId) los eventos persistidos junto con la transaccion de BD hacia Kafka
 * (jobs.events) o RabbitMQ (colas de comando), sin acoplar la disponibilidad del broker al
 * flujo transaccional de creacion/actualizacion de ordenes.
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final JobsProperties properties;

    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   RabbitTemplate rabbitTemplate,
                                   JobsProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${tallerpro.jobs.outbox.poll-fixed-delay-ms:2000}")
    public void publicarPendientes() {
        if (!properties.outbox().publisherEnabled()) {
            return;
        }
        List<OutboxEvent> pendientes = outboxEventRepository.findByEstadoOrderByFechaCreacionAsc(
                EstadoOutbox.PENDIENTE, PageRequest.of(0, properties.outbox().batchSize()));
        for (OutboxEvent evento : pendientes) {
            publicar(evento);
        }
    }

    @Transactional
    public void publicar(OutboxEvent evento) {
        try {
            if (evento.getCanal() == CanalEvento.KAFKA) {
                kafkaTemplate.send(evento.getDestino(), evento.getAggregateId().toString(), evento.getPayload()).get();
            } else {
                rabbitTemplate.convertAndSend(properties.rabbitmq().exchangeCmd(), evento.getDestino(), evento.getPayload(),
                        message -> {
                            message.getMessageProperties().setHeader("eventId", evento.getEventId());
                            return message;
                        });
            }
            evento.setEstado(EstadoOutbox.ENVIADO);
            evento.setFechaEnvio(Instant.now());
            evento.setUltimoError(null);
        } catch (Exception ex) {
            log.error("Fallo al publicar evento outbox {} ({}): {}", evento.getId(), evento.getEventType(), ex.getMessage());
            evento.setEstado(EstadoOutbox.FALLIDO.equals(evento.getEstado()) ? EstadoOutbox.FALLIDO : EstadoOutbox.PENDIENTE);
            evento.setIntentos(evento.getIntentos() + 1);
            evento.setUltimoError(ex.getMessage());
        }
        outboxEventRepository.save(evento);
    }
}
