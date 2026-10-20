package com.tallerpro.ms_tallerpro_jobs.service;

import com.tallerpro.ms_tallerpro_jobs.dto.BahiaDisponibilidadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integracion sincrona con ms-tallerpro-catalog (T-02.04, T-02.09 / RF-06, RF-08).
 * Ante fallos de comunicacion, degrada de forma controlada sin interrumpir el flujo
 * transaccional de ms-tallerpro-jobs, en linea con el principio de resiliencia de la
 * arquitectura (ADR-04).
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient catalogRestClient) {
        this.restClient = catalogRestClient;
    }

    /** RF-06: consulta si una bahia esta disponible en el taller antes de asignarla a una orden. */
    public Optional<BahiaDisponibilidadResponse> consultarDisponibilidadBahia(UUID tallerId, UUID bahiaId) {
        try {
            BahiaDisponibilidadResponse response = restClient.get()
                    .uri("/api/v1/talleres/{tallerId}/bahias/{bahiaId}/disponibilidad", tallerId, bahiaId)
                    .retrieve()
                    .body(BahiaDisponibilidadResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException ex) {
            log.warn("No fue posible consultar disponibilidad de bahia {} en taller {}: {}", bahiaId, tallerId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * RF-06: reserva la bahia para la orden (DISPONIBLE -> RESERVADA en catalog).
     * Devuelve false si catalog la rechaza (409: ya reservada/ocupada) o no responde.
     */
    public boolean reservarBahia(UUID tallerId, UUID bahiaId, UUID ordenId) {
        try {
            restClient.post()
                    .uri("/api/v1/talleres/{tallerId}/bahias/{bahiaId}/reserva", tallerId, bahiaId)
                    .body(new ReservaBahiaRequest(ordenId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            log.warn("No fue posible reservar la bahia {} en taller {} para la orden {}: {}", bahiaId, tallerId, ordenId, ex.getMessage());
            return false;
        }
    }

    /**
     * Marca la bahia como OCUPADA (RESERVADA -> OCUPADA) cuando el vehiculo
     * entra al puesto de trabajo. Lo dispara el diagnostico de la orden: la
     * ocupacion no se maneja a mano desde la pantalla de bahias.
     */
    public boolean ocuparBahia(UUID tallerId, UUID bahiaId) {
        try {
            restClient.post()
                    .uri("/api/v1/talleres/{tallerId}/bahias/{bahiaId}/ocupacion", tallerId, bahiaId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            log.warn("No fue posible ocupar la bahia {} en taller {}: {}", bahiaId, tallerId, ex.getMessage());
            return false;
        }
    }

    /** Libera la bahia al entregar o anular la orden (-> DISPONIBLE). Best-effort. */
    public boolean liberarBahia(UUID tallerId, UUID bahiaId) {
        try {
            restClient.post()
                    .uri("/api/v1/talleres/{tallerId}/bahias/{bahiaId}/liberacion", tallerId, bahiaId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            log.warn("No fue posible liberar la bahia {} en taller {}: {}", bahiaId, tallerId, ex.getMessage());
            return false;
        }
    }

    /** RF-08: solicita la disminucion de stock de un repuesto al diagnosticar la orden. */
    public boolean solicitarDisminucionStock(UUID tallerId, UUID repuestoId, int cantidad, String eventId) {
        try {
            restClient.post()
                    .uri("/api/v1/talleres/{tallerId}/repuestos/{repuestoId}/stock/decremento", tallerId, repuestoId)
                    .header("X-Event-Id", eventId)
                    .body(new DecrementoStockRequest(cantidad, eventId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            log.error("Fallo al solicitar disminucion de stock (repuesto={}, taller={}): {}", repuestoId, tallerId, ex.getMessage());
            return false;
        }
    }

    /**
     * Precio y nombre vigentes de un servicio del catalogo.
     *
     * El precio de lo que se cobra lo decide catalog, no el cliente HTTP: asi el
     * total de la orden no se puede manipular desde el navegador. Si catalog no
     * responde, se devuelve vacio y el item queda sin precio (subtotal 0) en vez
     * de bloquear el cierre de la orden.
     */
    public Optional<PrecioCatalogo> obtenerServicio(UUID servicioId) {
        try {
            ServicioResponse response = restClient.get()
                    .uri("/api/v1/servicios/{servicioId}", servicioId)
                    .retrieve()
                    .body(ServicioResponse.class);
            return response == null ? Optional.empty()
                    : Optional.of(new PrecioCatalogo(response.nombre(), response.precio()));
        } catch (RestClientException ex) {
            log.warn("No fue posible obtener el servicio {} del catalogo: {}", servicioId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Precio y nombre vigentes de un repuesto del taller (ver obtenerServicio). */
    public Optional<PrecioCatalogo> obtenerRepuesto(UUID tallerId, UUID repuestoId) {
        try {
            RepuestoResponse response = restClient.get()
                    .uri("/api/v1/talleres/{tallerId}/repuestos/{repuestoId}", tallerId, repuestoId)
                    .retrieve()
                    .body(RepuestoResponse.class);
            return response == null ? Optional.empty()
                    : Optional.of(new PrecioCatalogo(response.nombre(), response.precioUnitario()));
        } catch (RestClientException ex) {
            log.warn("No fue posible obtener el repuesto {} del taller {}: {}", repuestoId, tallerId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Estado actual de una bahia. jobs lo usa para distinguir "ocupada por otra
     * orden" de "ya reservada para esta misma orden" (ver reservarBahiaParaOrden).
     */
    public Optional<BahiaEstado> obtenerBahia(UUID tallerId, UUID bahiaId) {
        try {
            BahiaEstado response = restClient.get()
                    .uri("/api/v1/talleres/{tallerId}/bahias/{bahiaId}", tallerId, bahiaId)
                    .retrieve()
                    .body(BahiaEstado.class);
            return Optional.ofNullable(response);
        } catch (RestClientException ex) {
            log.warn("No fue posible obtener la bahia {} del taller {}: {}", bahiaId, tallerId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Solo para DemoDataSeeder (perfil "demo"): reutiliza la bahia de demo si ya
     * existe en el taller (por codigo) o la crea si es la primera vez. No se usa
     * en el flujo normal de ordenes (la creacion de bahias es RF-09, rol Admin).
     */
    public Optional<UUID> obtenerOCrearBahiaDemo(UUID tallerId, String codigo, String sector) {
        try {
            List<BahiaEstado> bahias = restClient.get()
                    .uri("/api/v1/talleres/{tallerId}/bahias", tallerId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<BahiaEstado>>() {});
            Optional<UUID> existente = (bahias == null ? List.<BahiaEstado>of() : bahias).stream()
                    .filter(b -> codigo.equals(b.codigo()))
                    .map(BahiaEstado::id)
                    .findFirst();
            if (existente.isPresent()) {
                return existente;
            }
        } catch (RestClientException ex) {
            log.warn("No fue posible listar bahias del taller {} (seeder de demo): {}", tallerId, ex.getMessage());
        }
        try {
            BahiaEstado creada = restClient.post()
                    .uri("/api/v1/talleres/{tallerId}/bahias", tallerId)
                    .body(new CrearBahiaRequest(codigo, sector, true))
                    .retrieve()
                    .body(BahiaEstado.class);
            return Optional.ofNullable(creada).map(BahiaEstado::id);
        } catch (RestClientException ex) {
            log.error("No fue posible crear la bahia de demo {} en taller {}: {}", codigo, tallerId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Subconjunto de BahiaResponse de catalog que a jobs le interesa. */
    public record BahiaEstado(UUID id, String codigo, String estado, UUID ordenId) {}

    private record CrearBahiaRequest(String codigo, String sector, boolean activa) {}

    /** Lo unico que jobs necesita del catalogo para cobrar: como se llama y cuanto vale. */
    public record PrecioCatalogo(String nombre, BigDecimal precio) {}

    private record ServicioResponse(String nombre, BigDecimal precio) {}

    private record RepuestoResponse(String nombre, BigDecimal precioUnitario) {}

    private record DecrementoStockRequest(int cantidad, String eventId) {}

    private record ReservaBahiaRequest(UUID ordenId) {}
}
