package com.tallerpro.ms_tallerpro_jobs.controller;

import com.tallerpro.ms_tallerpro_jobs.domain.EstadoOrden;
import com.tallerpro.ms_tallerpro_jobs.dto.AnularOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.AsignarRecursosRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.CrearOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.DiagnosticarOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.OrdenServicioResponse;
import com.tallerpro.ms_tallerpro_jobs.dto.RegistrarReparacionRequest;
import com.tallerpro.ms_tallerpro_jobs.service.OrdenServicioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * MS-01 - ms-tallerpro-jobs: gestion de ordenes de servicio (EP-02).
 *
 * Autorizacion por rol (caso, seccion 2 y 3):
 *  - Crear orden: Cliente, Jefe de taller, Admin.
 *  - Cambiar estado / asignar recursos: Jefe de taller, Admin (Mecanico puede diagnosticar y reparar).
 *  - Consultar: Admin, JefeTaller, Mecanico, Auditor; Cliente solo sus propias ordenes.
 */
@RestController
@RequestMapping("/api/v1/ordenes")
@Tag(name = "Ordenes de Servicio", description = "RF-04, RF-05, RF-06: CRUD, maquina de estados y asignacion de recursos")
public class OrdenServicioController {

    private static final String OPERADORES = "hasAnyRole('Admin', 'JefeTaller')";
    private static final String TALLER = "hasAnyRole('Admin', 'JefeTaller', 'Mecanico')";
    private static final String LECTURA = "hasAnyRole('Admin', 'JefeTaller', 'Mecanico', 'Auditor')";

    private final OrdenServicioService service;

    public OrdenServicioController(OrdenServicioService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('Admin', 'JefeTaller', 'Cliente')")
    @Operation(summary = "Crear orden de servicio (RF-04)")
    public ResponseEntity<OrdenServicioResponse> crear(@Valid @RequestBody CrearOrdenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crearOrden(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize(LECTURA)
    @Operation(summary = "Obtener una orden por id")
    public OrdenServicioResponse obtener(@PathVariable UUID id) {
        return service.obtenerOrden(id);
    }

    @GetMapping
    @PreAuthorize(LECTURA)
    @Operation(summary = "Listar ordenes, filtrando opcionalmente por taller y/o estado")
    public List<OrdenServicioResponse> listar(@RequestParam(required = false) UUID tallerId,
                                               @RequestParam(required = false) EstadoOrden estado) {
        return service.listarOrdenes(tallerId, estado);
    }

    /**
     * T-02.05. Alcance de datos (ARQUITECTURA_ACCESO.md, seccion 7): un Cliente solo puede
     * consultar ordenes cuyo clienteId coincide con el oid de SU token; el clienteId de la
     * ruta nunca se acepta a ciegas. Admin/JefeTaller pueden consultar cualquier cliente.
     */
    @GetMapping("/{id}/clientes/{clienteId}")
    @PreAuthorize("hasAnyRole('Admin', 'JefeTaller', 'Cliente')")
    @Operation(summary = "Consultar el estado del vehiculo (rol Cliente, T-02.05)")
    public OrdenServicioResponse consultarParaCliente(@PathVariable UUID id, @PathVariable UUID clienteId,
                                                      Authentication authentication) {
        verificarAlcanceCliente(authentication, clienteId);
        return service.consultarEstadoParaCliente(id, clienteId);
    }

    @PostMapping("/{id}/asignacion")
    @PreAuthorize(OPERADORES)
    @Operation(summary = "Asignar mecanico y bahia disponible a la orden (RF-06)")
    public OrdenServicioResponse asignar(@PathVariable UUID id, @Valid @RequestBody AsignarRecursosRequest request) {
        return service.asignarRecursos(id, request);
    }

    @PostMapping("/{id}/diagnostico")
    @PreAuthorize(TALLER)
    @Operation(summary = "Registrar diagnostico (RF-05: RECEPCIONADA -> DIAGNOSTICADA, dispara RF-08)")
    public OrdenServicioResponse diagnosticar(@PathVariable UUID id, @Valid @RequestBody DiagnosticarOrdenRequest request) {
        return service.diagnosticar(id, request);
    }

    @PostMapping("/{id}/reparacion")
    @PreAuthorize(TALLER)
    @Operation(summary = "Registrar reparacion (RF-05: DIAGNOSTICADA -> EN_REPARACION)")
    public OrdenServicioResponse registrarReparacion(@PathVariable UUID id,
                                                       @RequestBody(required = false) RegistrarReparacionRequest request) {
        return service.registrarReparacion(id, request == null ? new RegistrarReparacionRequest(null, null) : request);
    }

    @PostMapping("/{id}/lista-retiro")
    @PreAuthorize(TALLER)
    @Operation(summary = "Marcar orden lista para retiro (RF-05: EN_REPARACION -> LISTA_RETIRO)")
    public OrdenServicioResponse listaParaRetiro(@PathVariable UUID id) {
        return service.marcarListaParaRetiro(id);
    }

    @PostMapping("/{id}/entrega")
    @PreAuthorize(OPERADORES)
    @Operation(summary = "Entregar vehiculo al cliente (RF-05: LISTA_RETIRO -> ENTREGADA)")
    public OrdenServicioResponse entregar(@PathVariable UUID id) {
        return service.entregar(id);
    }

    @PostMapping("/{id}/anulacion")
    @PreAuthorize(OPERADORES)
    @Operation(summary = "Anular orden de servicio (T-02.06)")
    public OrdenServicioResponse anular(@PathVariable UUID id, @Valid @RequestBody AnularOrdenRequest request) {
        return service.anular(id, request);
    }

    private static void verificarAlcanceCliente(Authentication authentication, UUID clienteId) {
        boolean esOperador = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_Admin") || a.getAuthority().equals("ROLE_JefeTaller"));
        if (esOperador) {
            return;
        }
        // Solo aplica con JWT real: el oid de Azure AD es un GUID y se usa como clienteId.
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String oid = jwt.getToken().getClaimAsString("oid");
            if (oid == null || !oid.equalsIgnoreCase(clienteId.toString())) {
                throw new AccessDeniedException("La orden consultada no pertenece al cliente autenticado");
            }
        }
    }
}
