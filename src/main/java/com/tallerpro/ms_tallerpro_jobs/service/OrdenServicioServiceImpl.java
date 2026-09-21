package com.tallerpro.ms_tallerpro_jobs.service;

import tools.jackson.databind.ObjectMapper;
import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import com.tallerpro.ms_tallerpro_jobs.domain.CanalEvento;
import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.domain.OrdenServicio;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import com.tallerpro.ms_tallerpro_jobs.domain.RepuestoUtilizado;
import com.tallerpro.ms_tallerpro_jobs.dto.AnularOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.AsignarRecursosRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.BahiaDisponibilidadResponse;
import com.tallerpro.ms_tallerpro_jobs.dto.CrearOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.DiagnosticarOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.OrdenServicioResponse;
import com.tallerpro.ms_tallerpro_jobs.dto.RegistrarReparacionRequest;
import com.tallerpro.ms_tallerpro_jobs.event.JobsEventEnvelope;
import com.tallerpro.ms_tallerpro_jobs.exception.EstadoInvalidoException;
import com.tallerpro.ms_tallerpro_jobs.exception.OrdenNotFoundException;
import com.tallerpro.ms_tallerpro_jobs.exception.RecursoNoDisponibleException;
import com.tallerpro.ms_tallerpro_jobs.repository.OrdenServicioRepository;
import com.tallerpro.ms_tallerpro_jobs.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Nucleo transaccional de ordenes de servicio (EP-02). Coordina la maquina de estados (RF-05),
 * la asignacion de recursos (RF-06), la integracion con ms-tallerpro-catalog (RF-08) y la
 * publicacion de eventos/comandos via el patron Outbox (T-02.07, T-02.08, T-02.10).
 */
@Service
public class OrdenServicioServiceImpl implements OrdenServicioService {

    private static final Logger log = LoggerFactory.getLogger(OrdenServicioServiceImpl.class);

    private final OrdenServicioRepository ordenRepository;
    private final OutboxEventRepository outboxRepository;
    private final CatalogClient catalogClient;
    private final EstadoOrdenValidator estadoValidator;
    private final JobsProperties properties;
    private final ObjectMapper objectMapper;

    public OrdenServicioServiceImpl(OrdenServicioRepository ordenRepository,
                                     OutboxEventRepository outboxRepository,
                                     CatalogClient catalogClient,
                                     EstadoOrdenValidator estadoValidator,
                                     JobsProperties properties,
                                     ObjectMapper objectMapper) {
        this.ordenRepository = ordenRepository;
        this.outboxRepository = outboxRepository;
        this.catalogClient = catalogClient;
        this.estadoValidator = estadoValidator;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public OrdenServicioResponse crearOrden(CrearOrdenRequest request) {
        OrdenServicio orden = OrdenServicio.builder()
                .tallerId(request.tallerId())
                .clienteId(request.clienteId())
                .clienteNombre(request.clienteNombre())
                .clienteContacto(request.clienteContacto())
                .vehiculoPatente(request.vehiculoPatente())
                .vehiculoMarca(request.vehiculoMarca())
                .vehiculoModelo(request.vehiculoModelo())
                .vehiculoAnio(request.vehiculoAnio())
                .estado(EstadoOrden.RECEPCIONADA)
                .build();
        orden = ordenRepository.save(orden);

        registrarEventoJobs(orden, "OrdenCreada");
        registrarComandoRabbit(orden, properties.rabbitmq().queueEmail(),
                conContacto(orden, Map.of("tipo", "ORDEN_RECEPCIONADA", "ordenId", orden.getId(), "cliente", orden.getClienteNombre())));
        registrarComandoRabbit(orden, properties.rabbitmq().queueQuote(),
                Map.of("tipo", "PRESUPUESTO_INICIAL", "ordenId", orden.getId(), "patente", orden.getVehiculoPatente()));

        log.info("Orden {} creada para taller {}", orden.getId(), orden.getTallerId());
        return OrdenServicioResponse.from(orden);
    }

    @Override
    @Transactional(readOnly = true)
    public OrdenServicioResponse obtenerOrden(UUID id) {
        return OrdenServicioResponse.from(buscarOrden(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrdenServicioResponse> listarOrdenes(UUID tallerId, EstadoOrden estado) {
        List<OrdenServicio> ordenes;
        if (tallerId != null && estado != null) {
            ordenes = ordenRepository.findByTallerIdAndEstado(tallerId, estado);
        } else if (tallerId != null) {
            ordenes = ordenRepository.findByTallerId(tallerId);
        } else if (estado != null) {
            ordenes = ordenRepository.findByEstado(estado);
        } else {
            ordenes = ordenRepository.findAll();
        }
        return ordenes.stream().map(OrdenServicioResponse::from).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrdenServicioResponse consultarEstadoParaCliente(UUID id, UUID clienteId) {
        OrdenServicio orden = ordenRepository.findByIdAndClienteId(id, clienteId)
                .orElseThrow(() -> new OrdenNotFoundException(id));
        return OrdenServicioResponse.from(orden);
    }

    @Override
    @Transactional
    public OrdenServicioResponse asignarRecursos(UUID id, AsignarRecursosRequest request) {
        OrdenServicio orden = buscarOrden(id);
        if (orden.getEstado().esEstadoFinal()) {
            throw new EstadoInvalidoException("No es posible asignar recursos a una orden en estado " + orden.getEstado());
        }

        boolean disponible = catalogClient.consultarDisponibilidadBahia(orden.getTallerId(), request.bahiaId())
                .map(BahiaDisponibilidadResponse::disponible)
                .orElse(false);
        if (!disponible || !catalogClient.reservarBahia(orden.getTallerId(), request.bahiaId(), orden.getId())) {
            throw new RecursoNoDisponibleException("La bahia %s no esta disponible en el taller %s"
                    .formatted(request.bahiaId(), orden.getTallerId()));
        }

        orden.setMecanicoId(request.mecanicoId());
        orden.setMecanicoNombre(request.mecanicoNombre());
        orden.setBahiaId(request.bahiaId());
        orden = ordenRepository.save(orden);

        registrarEventoJobs(orden, "OrdenAsignada");
        registrarComandoRabbit(orden, properties.rabbitmq().queueBay(),
                Map.of("tipo", "TICKET_BAHIA", "ordenId", orden.getId(), "mecanicoId", orden.getMecanicoId(), "bahiaId", orden.getBahiaId()));

        return OrdenServicioResponse.from(orden);
    }

    @Override
    @Transactional
    public OrdenServicioResponse diagnosticar(UUID id, DiagnosticarOrdenRequest request) {
        OrdenServicio orden = buscarOrden(id);
        estadoValidator.validarTransicion(orden.getEstado(), EstadoOrden.DIAGNOSTICADA);

        orden.setDiagnostico(request.diagnostico());
        orden.setEstado(EstadoOrden.DIAGNOSTICADA);
        orden.setFechaDiagnostico(Instant.now());

        if (request.repuestos() != null) {
            for (DiagnosticarOrdenRequest.RepuestoRequerido r : request.repuestos()) {
                orden.getRepuestosUtilizados().add(new RepuestoUtilizado(r.repuestoId(), r.nombre(), r.cantidad()));
                boolean ok = catalogClient.solicitarDisminucionStock(orden.getTallerId(), r.repuestoId(),
                        r.cantidad() == null ? 1 : r.cantidad(), UUID.randomUUID().toString());
                if (!ok) {
                    log.warn("Disminucion de stock no confirmada para repuesto {} de la orden {}", r.repuestoId(), orden.getId());
                }
            }
        }

        orden = ordenRepository.save(orden);

        registrarEventoJobs(orden, "OrdenDiagnosticada");
        registrarComandoRabbit(orden, properties.rabbitmq().queueEmail(),
                conContacto(orden, Map.of("tipo", "ORDEN_DIAGNOSTICADA", "ordenId", orden.getId(), "diagnostico", orden.getDiagnostico())));
        registrarComandoRabbit(orden, properties.rabbitmq().queueQuote(),
                Map.of("tipo", "PRESUPUESTO_ACTUALIZADO", "ordenId", orden.getId(), "repuestos", orden.getRepuestosUtilizados().size()));

        return OrdenServicioResponse.from(orden);
    }

    @Override
    @Transactional
    public OrdenServicioResponse registrarReparacion(UUID id, RegistrarReparacionRequest request) {
        OrdenServicio orden = buscarOrden(id);
        estadoValidator.validarTransicion(orden.getEstado(), EstadoOrden.EN_REPARACION);

        if (request.repuestosAdicionales() != null) {
            for (var r : request.repuestosAdicionales()) {
                orden.getRepuestosUtilizados().add(new RepuestoUtilizado(r.repuestoId(), r.nombre(), r.cantidad()));
            }
        }
        orden.setEstado(EstadoOrden.EN_REPARACION);
        orden.setFechaEnReparacion(Instant.now());
        orden = ordenRepository.save(orden);

        registrarEventoJobs(orden, "OrdenEnReparacion");
        return OrdenServicioResponse.from(orden);
    }

    @Override
    @Transactional
    public OrdenServicioResponse marcarListaParaRetiro(UUID id) {
        OrdenServicio orden = buscarOrden(id);
        estadoValidator.validarTransicion(orden.getEstado(), EstadoOrden.LISTA_RETIRO);

        orden.setEstado(EstadoOrden.LISTA_RETIRO);
        orden.setFechaListaRetiro(Instant.now());
        orden = ordenRepository.save(orden);

        registrarEventoJobs(orden, "OrdenListaRetiro");
        registrarComandoRabbit(orden, properties.rabbitmq().queueEmail(),
                conContacto(orden, Map.of("tipo", "VEHICULO_LISTO", "ordenId", orden.getId())));
        registrarComandoRabbit(orden, properties.rabbitmq().queueQuote(),
                Map.of("tipo", "ORDEN_TRABAJO_FINAL", "ordenId", orden.getId()));

        return OrdenServicioResponse.from(orden);
    }

    @Override
    @Transactional
    public OrdenServicioResponse entregar(UUID id) {
        OrdenServicio orden = buscarOrden(id);
        estadoValidator.validarTransicion(orden.getEstado(), EstadoOrden.ENTREGADA);

        orden.setEstado(EstadoOrden.ENTREGADA);
        orden.setFechaEntrega(Instant.now());
        orden = ordenRepository.save(orden);
        liberarBahiaSiCorresponde(orden);

        registrarEventoJobs(orden, "OrdenEntregada");
        registrarComandoRabbit(orden, properties.rabbitmq().queueEmail(),
                conContacto(orden, Map.of("tipo", "ORDEN_ENTREGADA", "ordenId", orden.getId())));

        return OrdenServicioResponse.from(orden);
    }

    @Override
    @Transactional
    public OrdenServicioResponse anular(UUID id, AnularOrdenRequest request) {
        OrdenServicio orden = buscarOrden(id);
        if (orden.getEstado().esEstadoFinal()) {
            throw new EstadoInvalidoException("No es posible anular una orden en estado " + orden.getEstado());
        }
        estadoValidator.validarTransicion(orden.getEstado(), EstadoOrden.ANULADA);

        orden.setEstado(EstadoOrden.ANULADA);
        orden.setMotivoAnulacion(request.motivo());
        orden.setFechaAnulacion(Instant.now());
        orden = ordenRepository.save(orden);
        liberarBahiaSiCorresponde(orden);

        registrarEventoJobs(orden, "OrdenAnulada");
        registrarComandoRabbit(orden, properties.rabbitmq().queueEmail(),
                conContacto(orden, Map.of("tipo", "ORDEN_ANULADA", "ordenId", orden.getId(), "motivo", request.motivo())));

        return OrdenServicioResponse.from(orden);
    }

    /** Al cerrar la orden (entrega o anulacion) la bahia vuelve a estar disponible en catalog. */
    private void liberarBahiaSiCorresponde(OrdenServicio orden) {
        if (orden.getBahiaId() != null) {
            catalogClient.liberarBahia(orden.getTallerId(), orden.getBahiaId());
        }
    }

    private OrdenServicio buscarOrden(UUID id) {
        return ordenRepository.findById(id).orElseThrow(() -> new OrdenNotFoundException(id));
    }

    private void registrarEventoJobs(OrdenServicio orden, String tipoEvento) {
        String eventId = UUID.randomUUID().toString();
        Actor actor = actorActual();
        JobsEventEnvelope envelope = new JobsEventEnvelope(
                tipoEvento, eventId, Instant.now(), UUID.randomUUID().toString(), eventId,
                orden.getId(), orden.getId().toString(), orden.getTallerId().toString(), orden.getEstado(),
                actor.id(), actor.nombre(), actor.rol(),
                serializar(OrdenServicioResponse.from(orden)));
        OutboxEvent evento = OutboxEvent.builder()
                .aggregateType("OrdenServicio")
                .aggregateId(orden.getId())
                .eventType(tipoEvento)
                .canal(CanalEvento.KAFKA)
                .destino(properties.kafka().topicJobsEvents())
                .payload(serializar(envelope))
                .eventId(eventId)
                .correlationId(envelope.correlationId())
                .traceId(envelope.traceId())
                .build();
        outboxRepository.save(evento);
    }

    /** Los comandos de email llevan el contacto del cliente (si existe) para que notify sepa a quien escribir. */
    private static Map<String, Object> conContacto(OrdenServicio orden, Map<String, Object> base) {
        Map<String, Object> datos = new java.util.HashMap<>(base);
        if (orden.getClienteContacto() != null && !orden.getClienteContacto().isBlank()) {
            datos.put("contacto", orden.getClienteContacto());
        }
        datos.putIfAbsent("cliente", orden.getClienteNombre());
        return datos;
    }

    private record Actor(String id, String nombre, String rol) {}

    /**
     * Quien ejecuta la accion, para auditoria (RF-15): oid y nombre del JWT de Azure AD.
     * El app role se traduce al enum ActorRole de ms-tallerpro-audit.
     */
    private Actor actorActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return new Actor("sistema", "Sistema", "SISTEMA");
        }
        String id = auth.getName();
        String nombre = auth.getName();
        if (auth instanceof JwtAuthenticationToken jwt) {
            id = jwt.getToken().getClaimAsString("oid") != null ? jwt.getToken().getClaimAsString("oid") : id;
            nombre = jwt.getToken().getClaimAsString("name") != null ? jwt.getToken().getClaimAsString("name") : id;
        }
        String rol = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> switch (a.substring(5)) {
                    case "Admin" -> "ADMIN";
                    case "JefeTaller" -> "JEFE_TALLER";
                    case "Mecanico" -> "MECANICO";
                    case "Cliente" -> "CLIENTE";
                    default -> "SISTEMA";
                })
                .findFirst()
                .orElse("SISTEMA");
        return new Actor(id, nombre, rol);
    }

    private void registrarComandoRabbit(OrdenServicio orden, String routingKey, Object payload) {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent evento = OutboxEvent.builder()
                .aggregateType("OrdenServicio")
                .aggregateId(orden.getId())
                .eventType("Comando:" + routingKey)
                .canal(CanalEvento.RABBITMQ)
                .destino(routingKey)
                .payload(serializar(payload))
                .eventId(eventId)
                .build();
        outboxRepository.save(evento);
    }

    private String serializar(Object objeto) {
        try {
            return objectMapper.writeValueAsString(objeto);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible serializar el evento de dominio", ex);
        }
    }
}
