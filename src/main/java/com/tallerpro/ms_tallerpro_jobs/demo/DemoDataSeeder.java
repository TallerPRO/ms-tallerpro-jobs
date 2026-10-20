package com.tallerpro.ms_tallerpro_jobs.demo;

import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import com.tallerpro.ms_tallerpro_jobs.dto.CrearOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.DiagnosticarOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.OrdenServicioResponse;
import com.tallerpro.ms_tallerpro_jobs.dto.RegistrarReparacionRequest;
import com.tallerpro.ms_tallerpro_jobs.service.CatalogClient;
import com.tallerpro.ms_tallerpro_jobs.service.OrdenServicioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seeder de demo (perfil "demo", ver T-02.13): recorre de extremo a extremo una
 * orden (recepcion -> diagnostico -> reparacion -> lista para retiro -> entrega)
 * contra el ms-tallerpro-catalog y ms-tallerpro-notify reales, para disparar las
 * 5 notificaciones (RF-10, RF-11) sin necesitar curl/Postman.
 *
 * Nunca se ejecuta fuera del perfil "demo": en produccion/dev normal este bean
 * ni siquiera se instancia. Los correos de cliente y mecanico se configuran en
 * tallerpro.jobs.demo.* (application.yml) y se pueden cambiar ahi sin tocar esta
 * clase.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final UUID TALLER_DEMO_ID = UUID.nameUUIDFromBytes("TallerPro-Demo-Taller".getBytes());
    private static final UUID CLIENTE_DEMO_ID = UUID.nameUUIDFromBytes("TallerPro-Demo-Cliente".getBytes());
    private static final UUID MECANICO_DEMO_ID = UUID.nameUUIDFromBytes("TallerPro-Demo-Mecanico".getBytes());
    private static final String BAHIA_DEMO_CODIGO = "DEMO-01";

    private final OrdenServicioService ordenServicioService;
    private final CatalogClient catalogClient;
    private final JobsProperties properties;

    public DemoDataSeeder(OrdenServicioService ordenServicioService, CatalogClient catalogClient, JobsProperties properties) {
        this.ordenServicioService = ordenServicioService;
        this.catalogClient = catalogClient;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        String clienteEmail = properties.demo().clienteEmail();
        String mecanicoEmail = properties.demo().mecanicoEmail();
        log.info("=== Seeder de demo activo (perfil 'demo'): cliente={} mecanico={} ===", clienteEmail, mecanicoEmail);

        UUID bahiaId = catalogClient.obtenerOCrearBahiaDemo(TALLER_DEMO_ID, BAHIA_DEMO_CODIGO, "Demo")
                .orElseThrow(() -> new IllegalStateException(
                        "No fue posible obtener/crear la bahia de demo en ms-tallerpro-catalog. ¿Esta corriendo?"));

        // Patente con sufijo de tiempo: cada arranque con perfil demo crea una
        // orden nueva (y reenvia los 5 correos), en vez de fallar por duplicado.
        String patenteDemo = "DEMO" + (System.currentTimeMillis() % 100000);

        CrearOrdenRequest crear = new CrearOrdenRequest(
                TALLER_DEMO_ID, CLIENTE_DEMO_ID, "Cliente Demo", clienteEmail, "+56900000000",
                patenteDemo, "Toyota", "Yaris", 2020,
                bahiaId, MECANICO_DEMO_ID, "Mecanico Demo", mecanicoEmail);
        OrdenServicioResponse orden = ordenServicioService.crearOrden(crear);
        log.info("[DEMO] Orden {} creada (patente {}) -> email ORDEN_RECEPCIONADA al cliente", orden.id(), patenteDemo);

        orden = ordenServicioService.diagnosticar(orden.id(), new DiagnosticarOrdenRequest(
                "Pastillas de freno delanteras desgastadas, requiere cambio.", List.of(), List.of()));
        log.info("[DEMO] Orden {} diagnosticada -> email ORDEN_DIAGNOSTICADA al cliente", orden.id());

        orden = ordenServicioService.registrarReparacion(orden.id(), new RegistrarReparacionRequest(
                "Cliente autoriza cambio de pastillas. Revisar discos tambien.", List.of()));
        log.info("[DEMO] Orden {} en reparacion -> TICKET_BAHIA al mecanico", orden.id());

        orden = ordenServicioService.marcarListaParaRetiro(orden.id());
        log.info("[DEMO] Orden {} lista para retiro -> email VEHICULO_LISTO al cliente", orden.id());

        orden = ordenServicioService.entregar(orden.id());
        log.info("[DEMO] Orden {} entregada -> email ORDEN_ENTREGADA al cliente", orden.id());

        log.info("=== Seeder de demo completo: revisa {} y {} (y los logs de ms-tallerpro-notify) ===", clienteEmail, mecanicoEmail);
    }
}
