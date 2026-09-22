package com.tallerpro.ms_tallerpro_jobs.service;

import tools.jackson.databind.ObjectMapper;
import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import com.tallerpro.ms_tallerpro_jobs.domain.CanalEvento;
import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.domain.OrdenServicio;
import com.tallerpro.ms_tallerpro_jobs.domain.OutboxEvent;
import com.tallerpro.ms_tallerpro_jobs.domain.RepuestoUtilizado;
import com.tallerpro.ms_tallerpro_jobs.domain.ServicioAplicado;
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
import java.util.LinkedHashMap;
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
                .clienteTelefono(request.clienteTelefono())
                .mecanicoId(request.mecanicoId())
                .mecanicoNombre(request.mecanicoNombre())
                .mecanicoContacto(request.mecanicoContacto())
                .vehiculoPatente(request.vehiculoPatente())
                .vehiculoMarca(request.vehiculoMarca())
                .vehiculoModelo(request.vehiculoModelo())
                .vehiculoAnio(request.vehiculoAnio())
                .estado(EstadoOrden.RECEPCIONADA)
                .build();
        orden = ordenRepository.save(orden);

        // RF-06: si la recepcion indica bahia, se reserva en el mismo acto. La
        // reserva va despues del save porque catalog necesita el id de la orden.
        // Si catalog la rechaza, la excepcion revierte la transaccion y la orden
        // no queda creada: preferimos fallar a recepcionar un vehiculo sin puesto.
        if (request.bahiaId() != null) {
            reservarBahiaParaOrden(orden, request.bahiaId());
            orden.setBahiaId(request.bahiaId());
            orden = ordenRepository.save(orden);
        }

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

        reservarBahiaParaOrden(orden, request.bahiaId());

        orden.setMecanicoId(request.mecanicoId());
        orden.setMecanicoNombre(request.mecanicoNombre());
        if (request.mecanicoContacto() != null && !request.mecanicoContacto().isBlank()) {
            orden.setMecanicoContacto(request.mecanicoContacto());
        }
        orden.setBahiaId(request.bahiaId());
        orden = ordenRepository.save(orden);

        registrarEventoJobs(orden, "OrdenAsignada");

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
                // Precio del catalogo para que el total de la orden cuadre desde el diagnostico.
                var datos = catalogClient.obtenerRepuesto(orden.getTallerId(), r.repuestoId());
                orden.getRepuestosUtilizados().add(new RepuestoUtilizado(
                        r.repuestoId(),
                        datos.map(CatalogClient.PrecioCatalogo::nombre).orElse(r.nombre()),
                        r.cantidad(),
                        datos.map(CatalogClient.PrecioCatalogo::precio).orElse(null)));

                boolean ok = catalogClient.solicitarDisminucionStock(orden.getTallerId(), r.repuestoId(),
                        r.cantidad() == null ? 1 : r.cantidad(), UUID.randomUUID().toString());
                if (!ok) {
                    log.warn("Disminucion de stock no confirmada para repuesto {} de la orden {}", r.repuestoId(), orden.getId());
                }
            }
        }

        if (request.servicios() != null) {
            for (DiagnosticarOrdenRequest.ServicioRequerido s : request.servicios()) {
                var datos = catalogClient.obtenerServicio(s.servicioId());
                orden.getServiciosAplicados().add(new ServicioAplicado(
                        s.servicioId(),
                        datos.map(CatalogClient.PrecioCatalogo::nombre).orElse("Servicio " + s.servicioId()),
                        s.cantidad(),
                        datos.map(CatalogClient.PrecioCatalogo::precio).orElse(null)));
            }
        }

        orden.recalcularTotal();
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
                var datos = catalogClient.obtenerRepuesto(orden.getTallerId(), r.repuestoId());
                orden.getRepuestosUtilizados().add(new RepuestoUtilizado(
                        r.repuestoId(),
                        datos.map(CatalogClient.PrecioCatalogo::nombre).orElse(r.nombre()),
                        r.cantidad(),
                        datos.map(CatalogClient.PrecioCatalogo::precio).orElse(null)));
            }
            orden.recalcularTotal();
        }
        orden.setEstado(EstadoOrden.EN_REPARACION);
        orden.setFechaEnReparacion(Instant.now());
        orden = ordenRepository.save(orden);

        // Empieza el trabajo: el vehiculo entra a su bahia (queda OCUPADA) y
        // recien aqui se le avisa al mecanico, con el diagnostico y las
        // observaciones, que es lo que necesita para trabajar. La ocupacion se
        // maneja siempre desde la orden, nunca a mano desde el mapa de bahias.
        if (orden.getBahiaId() != null) {
            catalogClient.ocuparBahia(orden.getTallerId(), orden.getBahiaId());
            registrarComandoRabbit(orden, properties.rabbitmq().queueBay(),
                    comandoTicketBahia(orden, request.observaciones()));
        }

        registrarEventoJobs(orden, "OrdenEnReparacion");
        return OrdenServicioResponse.from(orden);
    }

    /**
     * Fin del trabajo (RF-05/RF-06): la orden queda LISTA_RETIRO, se avisa al
     * cliente con el monto a pagar y se libera la bahia. Liberar el puesto es
     * consecuencia de este paso, no una accion suelta del mapa de bahias.
     */
    @Override
    @Transactional
    public OrdenServicioResponse marcarListaParaRetiro(UUID id) {
        OrdenServicio orden = buscarOrden(id);
        estadoValidator.validarTransicion(orden.getEstado(), EstadoOrden.LISTA_RETIRO);

        orden.setEstado(EstadoOrden.LISTA_RETIRO);
        orden.setFechaListaRetiro(Instant.now());
        orden = ordenRepository.save(orden);

        liberarBahiaSiCorresponde(orden);

        registrarEventoJobs(orden, "OrdenListaRetiro");
        // Aviso al cliente de que el vehiculo esta listo, con el monto a pagar.
        registrarComandoRabbit(orden, properties.rabbitmq().queueEmail(),
                conContacto(orden, Map.of("tipo", "VEHICULO_LISTO", "ordenId", orden.getId(), "total", orden.getTotal())));
        registrarComandoRabbit(orden, properties.rabbitmq().queueQuote(),
                Map.of("tipo", "ORDEN_TRABAJO_FINAL", "ordenId", orden.getId(), "total", orden.getTotal()));

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

    /**
     * Reserva la bahia para la orden, o la acepta si ya estaba tomada por esta
     * misma orden.
     *
     * Esa idempotencia es necesaria desde que la recepcion reserva la bahia al
     * crear la orden: al asignar despues el mecanico, la bahia ya figura
     * RESERVADA (u OCUPADA si el vehiculo entro) y catalog respondaria 409.
     * Solo se rechaza cuando la bahia esta tomada por OTRA orden.
     */
    private void reservarBahiaParaOrden(OrdenServicio orden, UUID bahiaId) {
        boolean disponible = catalogClient.consultarDisponibilidadBahia(orden.getTallerId(), bahiaId)
                .map(BahiaDisponibilidadResponse::disponible)
                .orElse(false);

        if (disponible) {
            if (!catalogClient.reservarBahia(orden.getTallerId(), bahiaId, orden.getId())) {
                throw new RecursoNoDisponibleException("La bahia %s no esta disponible en el taller %s"
                        .formatted(bahiaId, orden.getTallerId()));
            }
            return;
        }

        boolean yaEsDeEstaOrden = catalogClient.obtenerBahia(orden.getTallerId(), bahiaId)
                .map(b -> orden.getId().equals(b.ordenId()))
                .orElse(false);
        if (!yaEsDeEstaOrden) {
            throw new RecursoNoDisponibleException("La bahia %s no esta disponible en el taller %s"
                    .formatted(bahiaId, orden.getTallerId()));
        }
    }

    /**
     * Comando para notify: el aviso al mecanico de que tiene trabajo.
     *
     * Lleva el codigo visible de la bahia ("A-01"), no el UUID, porque es lo
     * que el mecanico reconoce; el codigo se lee del catalogo al construirlo.
     */
    private Map<String, Object> comandoTicketBahia(OrdenServicio orden, String observaciones) {
        Map<String, Object> comando = new LinkedHashMap<>();
        comando.put("tipo", "TICKET_BAHIA");
        comando.put("ordenId", orden.getId());
        comando.put("mecanicoId", orden.getMecanicoId());
        comando.put("bahiaId", orden.getBahiaId());
        comando.put("patente", orden.getVehiculoPatente());
        // El detalle del trabajo: el diagnostico y, si la hay, la nota del paso.
        if (orden.getDiagnostico() != null) {
            comando.put("diagnostico", orden.getDiagnostico());
        }
        if (observaciones != null && !observaciones.isBlank()) {
            comando.put("observaciones", observaciones);
        }
        if (orden.getMecanicoNombre() != null) {
            comando.put("mecanicoNombre", orden.getMecanicoNombre());
        }
        if (orden.getMecanicoContacto() != null && !orden.getMecanicoContacto().isBlank()) {
            comando.put("contacto", orden.getMecanicoContacto());
        }
        catalogClient.obtenerBahia(orden.getTallerId(), orden.getBahiaId())
                .ifPresent(b -> comando.put("bahiaCodigo", b.codigo()));
        return comando;
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
