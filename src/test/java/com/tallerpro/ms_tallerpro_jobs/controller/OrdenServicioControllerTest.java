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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
                tallerId, clienteId, "Juan Perez", "juan@example.com", "ABCD12", "Toyota", "Yaris", 2020);

        String responseBody = mockMvc.perform(post("/api/v1/ordenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("RECEPCIONADA"))
                .andReturn().getResponse().getContentAsString();

        UUID ordenId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        mockMvc.perform(post("/api/v1/ordenes/{id}/asignacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AsignarRecursosRequest(mecanicoId, "Pedro", bahiaId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bahiaId").value(bahiaId.toString()));

        DiagnosticarOrdenRequest diagnostico = new DiagnosticarOrdenRequest("Pastillas de freno desgastadas",
                List.of(new DiagnosticarOrdenRequest.RepuestoRequerido(UUID.randomUUID(), "Pastillas de freno", 1)));
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
                tallerId, clienteId, "Maria Rojas", "maria@example.com", "XYZ987", "Nissan", "Versa", 2019);

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
                tallerId, clienteId, "Carlos Diaz", "carlos@example.com", "QWER11", "Chevrolet", "Sail", 2018);
        String responseBody = mockMvc.perform(post("/api/v1/ordenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearRequest)))
                .andReturn().getResponse().getContentAsString();
        UUID ordenId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        mockMvc.perform(post("/api/v1/ordenes/{id}/asignacion", ordenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AsignarRecursosRequest(mecanicoId, "Pedro", bahiaId))));
        mockMvc.perform(post("/api/v1/ordenes/{id}/diagnostico", ordenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DiagnosticarOrdenRequest("Revision general", List.of()))));
        mockMvc.perform(post("/api/v1/ordenes/{id}/reparacion", ordenId).contentType(MediaType.APPLICATION_JSON));
        mockMvc.perform(post("/api/v1/ordenes/{id}/lista-retiro", ordenId));
        mockMvc.perform(post("/api/v1/ordenes/{id}/entrega", ordenId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/ordenes/{id}/anulacion", ordenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnularOrdenRequest("Cliente se arrepintio"))))
                .andExpect(status().isConflict());
    }
}
