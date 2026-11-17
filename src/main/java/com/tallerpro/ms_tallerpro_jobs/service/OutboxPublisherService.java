package com.tallerpro.ms_tallerpro_jobs.service;

import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import com.tallerpro.ms_tallerpro_jobs.domain.CanalEvento;
import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOutbox;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import com.tallerpro.ms_tallerpro_jobs.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Publicador del patron Outbox (T-02.08, ADR-07): entrega de forma asincrona y con reintentos
 * idempotentes (eventId) los eventos persistidos junto con la transaccion de BD hacia Kafka
 * (jobs.events) o RabbitMQ (colas de comando), sin acoplar la disponibilidad del broker al
 * flujo transaccional de creacion/actualizacion de ordenes.
 *
 * Reintentos (guia 3.3.3, "Control de Reintentos"):
 *  - Backoff exponencial por evento: backoffInicialMs, x2 en cada fallo, con tope backoffMaxMs.
 *    Un evento en espera no se vuelve a tomar hasta su proximoIntento, asi un broker caido no
 *    recibe una tormenta de reintentos cada pollFixedDelayMs.
 *  - Al agotar maxIntentos el evento pasa a FALLIDO (el equivalente a un DLT del outbox):
 *    deja de reintentarse y queda con ultimoError para analisis.
 *  - Un evento en espera no bloquea a los de otras ordenes (sin head-of-line blocking), pero si
 *    a los posteriores de la MISMA orden hacia el mismo destino, para no desordenar jobs.events.
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final JobsProperties properties;
    private final Clock clock;

    @Autowired
    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  RabbitTemplate rabbitTemplate,
                                  JobsProperties properties) {
        this(outboxEventRepository, kafkaTemplate, rabbitTemplate, properties, Clock.systemUTC());
    }

    OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           RabbitTemplate rabbitTemplate,
                           JobsProperties properties,
                           Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tallerpro.jobs.outbox.poll-fixed-delay-ms:2000}")
    public void publicarPendientes() {
        if (!properties.outbox().publisherEnabled()) {
            return;
        }
        List<OutboxEvent> pendientes = outboxEventRepository.findPublicables(
                clock.instant(), PageRequest.of(0, properties.outbox().batchSize()));
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
            evento.setFechaEnvio(clock.instant());
            evento.setProximoIntento(null);
            evento.setUltimoError(null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            registrarFallo(evento, ex);
        } catch (Exception ex) {
            registrarFallo(evento, ex);
        }
        outboxEventRepository.save(evento);
    }

    private void registrarFallo(OutboxEvent evento, Exception ex) {
        int intentos = (evento.getIntentos() == null ? 0 : evento.getIntentos()) + 1;
        int maxIntentos = properties.outbox().maxIntentos();
        evento.setIntentos(intentos);
        evento.setUltimoError(ex.getMessage());

        if (intentos >= maxIntentos) {
            evento.setEstado(EstadoOutbox.FALLIDO);
            evento.setProximoIntento(null);
            log.error("Fallo definitivo del evento outbox {} ({}, eventId={}) tras {} intentos; queda FALLIDO: {}",
                    evento.getId(), evento.getEventType(), evento.getEventId(), intentos, ex.getMessage());
            return;
        }

        Duration espera = backoff(intentos);
        evento.setEstado(EstadoOutbox.PENDIENTE);
        evento.setProximoIntento(clock.instant().plus(espera));
        log.warn("Fallo al publicar evento outbox {} ({}, eventId={}), reintento {}/{} en {} ms: {}",
                evento.getId(), evento.getEventType(), evento.getEventId(), intentos, maxIntentos,
                espera.toMillis(), ex.getMessage());
    }

    /** Espera tras el intento fallido n (1, 2, 3...): inicial * 2^(n-1), con tope. */
    Duration backoff(int intentoFallido) {
        long inicial = properties.outbox().backoffInicialMs();
        long maximo = properties.outbox().backoffMaxMs();
        int exponente = Math.min(intentoFallido - 1, 30); // evita overflow del shift
        long espera = inicial << exponente;
        if (espera <= 0 || espera > maximo) {
            espera = maximo;
        }
        return Duration.ofMillis(espera);
    }
}
