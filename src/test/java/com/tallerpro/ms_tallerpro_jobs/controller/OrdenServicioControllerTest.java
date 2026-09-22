package com.tallerpro.ms_tallerpro_jobs.controller;

import tools.jackson.databind.ObjectMapper;
import com.tallerpro.ms_tallerpro_jobs.dto.AnularOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.AsignarRecursosRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.BahiaDisponibilidadResponse;
import com.tallerpro.ms_tallerpro_jobs.dto.CrearOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.dto.DiagnosticarOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.service.CatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-02.13: test de integracion del flujo completo de una orden de servicio, desde su creacion
 * (RF-04) hasta la entrega, pasando por la maquina de estados (RF-05) y la asignacion de
 * recursos (RF-06). Kafka/RabbitMQ se simulan con mocks para no requerir brokers reales.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrdenServicioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CatalogClient catalogClient;

    @SuppressWarnings("unchecked")
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Test
    void creaOrdenYSiguenElFlujoCompletoHastaLaEntrega() throws Exception {
        UUID tallerId = UUID.randomUUID();
        UUID clienteId = UUID.randomUUID();
        UUID bahiaId = UUID.randomUUID();
        UUID mecanicoId = UUID.randomUUID();

        given(catalogClient.consultarDisponibilidadBahia(any(), any()))
                .willReturn(Optional.of(new BahiaDisponibilidadResponse(bahiaId, tallerId, true)));
        given(catalogClient.solicitarDisminucionStock(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .willReturn(true);
        given(catalogClient.reservarBahia(any(), any(), any())).willReturn(true);
        given(catalogClient.liberarBahia(any(), any())).willReturn(true);

        CrearOrdenRequest crearRequest = new CrearOrdenRequest(
                tallerId, clienteId, "Juan Perez", "juan@example.com", "+56911111111", "ABCD12", "Toyota", "Yaris", 2020, null, null, null, null);

        String responseBody = mockMvc.perform(post("/api/v1/ordenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("RECEPCIONADA"))
                .andReturn().getResponse().getContentAsString();

        UUID ordenId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        mockMvc.perform(post("/api/v1/ordenes/{id}/asignacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AsignarRecursosRequest(mecanicoId, "Pedro", "pedro@taller.cl", bahiaId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bahiaId").value(bahiaId.toString()));

        DiagnosticarOrdenRequest diagnostico = new DiagnosticarOrdenRequest("Pastillas de freno desgastadas",
                List.of(new DiagnosticarOrdenRequest.RepuestoRequerido(UUID.randomUUID(), "Pastillas de freno", 1)), List.of());
        mockMvc.perform(post("/api/v1/ordenes/{id}/diagnostico", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diagnostico)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DIAGNOSTICADA"));

        mockMvc.perform(post("/api/v1/ordenes/{id}/reparacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_REPARACION"));

        mockMvc.perform(post("/api/v1/ordenes/{id}/lista-retiro", ordenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("LISTA_RETIRO"));

        mockMvc.perform(post("/api/v1/ordenes/{id}/entrega", ordenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADA"));

        mockMvc.perform(get("/api/v1/ordenes/{id}/clientes/{clienteId}", ordenId, clienteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADA"));
    }

    @Test
    void noPermiteSaltarDiagnosticoParaPasarAEnReparacion() throws Exception {
        UUID tallerId = UUID.randomUUID();
        UUID clienteId = UUID.randomUUID();
        CrearOrdenRequest crearRequest = new CrearOrdenRequest(
                tallerId, clienteId, "Maria Rojas", "maria@example.com", "+56911111112", "XYZ987", "Nissan", "Versa", 2019, null, null, null, null);

        String responseBody = mockMvc.perform(post("/api/v1/ordenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID ordenId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        mockMvc.perform(post("/api/v1/ordenes/{id}/reparacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }

    @Test
    void noPermiteAnularUnaOrdenYaEntregada() throws Exception {
        UUID tallerId = UUID.randomUUID();
        UUID clienteId = UUID.randomUUID();
        UUID bahiaId = UUID.randomUUID();
        UUID mecanicoId = UUID.randomUUID();

        given(catalogClient.consultarDisponibilidadBahia(any(), any()))
                .willReturn(Optional.of(new BahiaDisponibilidadResponse(bahiaId, tallerId, true)));
        given(catalogClient.solicitarDisminucionStock(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .willReturn(true);
        given(catalogClient.reservarBahia(any(), any(), any())).willReturn(true);
        given(catalogClient.liberarBahia(any(), any())).willReturn(true);

        CrearOrdenRequest crearRequest = new CrearOrdenRequest(
                tallerId, clienteId, "Carlos Diaz", "carlos@example.com", "+56911111113", "QWER11", "Chevrolet", "Sail", 2018, null, null, null, null);
        String responseBody = mockMvc.perform(post("/api/v1/ordenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearRequest)))
                .andReturn().getResponse().getContentAsString();
        UUID ordenId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        mockMvc.perform(post("/api/v1/ordenes/{id}/asignacion", ordenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AsignarRecursosRequest(mecanicoId, "Pedro", "pedro@taller.cl", bahiaId))));
        mockMvc.perform(post("/api/v1/ordenes/{id}/diagnostico", ordenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DiagnosticarOrdenRequest("Revision general", List.of(), List.of()))));
        mockMvc.perform(post("/api/v1/ordenes/{id}/reparacion", ordenId).contentType(MediaType.APPLICATION_JSON));
        mockMvc.perform(post("/api/v1/ordenes/{id}/lista-retiro", ordenId));
        mockMvc.perform(post("/api/v1/ordenes/{id}/entrega", ordenId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/ordenes/{id}/anulacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnularOrdenRequest("Cliente se arrepintio"))))
                .andExpect(status().isConflict());
    }

    /**
     * Flujo operativo: se recepciona con bahia y mecanico, el diagnostico
     * registra repuestos y servicios (precios del catalogo, no del request) y
     * ocupa la bahia, y la salida la libera y avisa al cliente.
     */
    @Test
    void diagnosticoTotalizaYOcupaLaBahiaYLaSalidaLaLibera() throws Exception {
        UUID tallerId = UUID.randomUUID();
        UUID clienteId = UUID.randomUUID();
        UUID bahiaId = UUID.randomUUID();
        UUID mecanicoId = UUID.randomUUID();
        UUID repuestoId = UUID.randomUUID();
        UUID servicioId = UUID.randomUUID();

        given(catalogClient.consultarDisponibilidadBahia(any(), any()))
                .willReturn(Optional.of(new BahiaDisponibilidadResponse(bahiaId, tallerId, true)));
        given(catalogClient.reservarBahia(any(), any(), any())).willReturn(true);
        given(catalogClient.solicitarDisminucionStock(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .willReturn(true);
        given(catalogClient.obtenerRepuesto(any(), any()))
                .willReturn(Optional.of(new CatalogClient.PrecioCatalogo("Pastillas de freno", new BigDecimal("25000"))));
        given(catalogClient.obtenerServicio(any()))
                .willReturn(Optional.of(new CatalogClient.PrecioCatalogo("Cambio de pastillas", new BigDecimal("18000"))));

        String responseBody = mockMvc.perform(post("/api/v1/ordenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CrearOrdenRequest(
                                tallerId, clienteId, "Ana Soto", "ana@example.com", "+56911111114",
                                "CD5678", "Mazda", "3", 2021, bahiaId, mecanicoId, "Pedro Soto", "pedro@taller.cl"))))
                .andExpect(status().isCreated())
                // La bahia indicada en la recepcion queda registrada en la orden.
                .andExpect(jsonPath("$.bahiaId").value(bahiaId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID ordenId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        // El diagnostico trae los items: 2 x 25.000 + 1 x 18.000 = 68.000
        mockMvc.perform(post("/api/v1/ordenes/{id}/diagnostico", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiagnosticarOrdenRequest(
                                "Frenos gastados",
                                List.of(new DiagnosticarOrdenRequest.RepuestoRequerido(repuestoId, null, 2)),
                                List.of(new DiagnosticarOrdenRequest.ServicioRequerido(servicioId, 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DIAGNOSTICADA"))
                .andExpect(jsonPath("$.repuestosUtilizados[0].nombre").value("Pastillas de freno"))
                .andExpect(jsonPath("$.repuestosUtilizados[0].precioUnitario").value(25000))
                .andExpect(jsonPath("$.serviciosAplicados[0].nombre").value("Cambio de pastillas"))
                .andExpect(jsonPath("$.total").value(68000));

        // Pasar a reparacion ocupa la bahia: no se hace a mano desde la pantalla de bahias.
        mockMvc.perform(post("/api/v1/ordenes/{id}/reparacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_REPARACION"));
        verify(catalogClient).ocuparBahia(tallerId, bahiaId);

        // Lista para retiro libera la bahia y avisa al cliente.
        mockMvc.perform(post("/api/v1/ordenes/{id}/lista-retiro", ordenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("LISTA_RETIRO"))
                .andExpect(jsonPath("$.total").value(68000));
        verify(catalogClient).liberarBahia(tallerId, bahiaId);
    }

    /**
     * Regresion: desde que la recepcion reserva la bahia, al asignar el mecanico
     * catalog ya no la reporta disponible. Debe aceptarse igual si la bahia esta
     * tomada por ESTA orden, y solo rechazarse si es de otra.
     */
    @Test
    void permiteAsignarMecanicoSobreLaBahiaQueYaReservoLaPropiaOrden() throws Exception {
        UUID tallerId = UUID.randomUUID();
        UUID bahiaId = UUID.randomUUID();

        given(catalogClient.consultarDisponibilidadBahia(any(), any()))
                .willReturn(Optional.of(new BahiaDisponibilidadResponse(bahiaId, tallerId, true)));
        given(catalogClient.reservarBahia(any(), any(), any())).willReturn(true);

        String responseBody = mockMvc.perform(post("/api/v1/ordenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CrearOrdenRequest(
                                tallerId, UUID.randomUUID(), "Luis Vera", "luis@example.com", "+56911111115",
                                "EF9012", "Kia", "Rio", 2020, bahiaId, UUID.randomUUID(), "Pedro Soto", "pedro@taller.cl"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID ordenId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        // A partir de aqui catalog ya no la da por disponible: esta reservada para esta orden.
        given(catalogClient.consultarDisponibilidadBahia(any(), any()))
                .willReturn(Optional.of(new BahiaDisponibilidadResponse(bahiaId, tallerId, false)));
        given(catalogClient.obtenerBahia(any(), any()))
                .willReturn(Optional.of(new CatalogClient.BahiaEstado(bahiaId, "A-01", "RESERVADA", ordenId)));

        mockMvc.perform(post("/api/v1/ordenes/{id}/asignacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AsignarRecursosRequest(UUID.randomUUID(), "Pedro Mecanico", "pedro@taller.cl", bahiaId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mecanicoNombre").value("Pedro Mecanico"));

        // Si la bahia fuera de otra orden, se rechaza.
        given(catalogClient.obtenerBahia(any(), any()))
                .willReturn(Optional.of(new CatalogClient.BahiaEstado(bahiaId, "A-01", "OCUPADA", UUID.randomUUID())));

        mockMvc.perform(post("/api/v1/ordenes/{id}/asignacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AsignarRecursosRequest(UUID.randomUUID(), "Otro", "otro@taller.cl", bahiaId))))
                .andExpect(status().isConflict());
    }
}
