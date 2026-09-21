package com.tallerpro.ms_tallerpro_jobs.controller;

import com.tallerpro.ms_tallerpro_jobs.dto.CrearOrdenRequest;
import com.tallerpro.ms_tallerpro_jobs.service.CatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Evidencia de la capa de autorizacion (ARQUITECTURA_ACCESO.md, secciones 6, 7 y 9)
 * con la validacion JWT ACTIVA: 401 sin token, 403 por rol insuficiente y 403 cuando un
 * Cliente intenta consultar una orden de otro cliente (alcance por oid).
 */
@SpringBootTest(properties = {
        "tallerpro.security.jwt-enabled=true",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrdenServicioSeguridadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalogClient;

    @SuppressWarnings("unchecked")
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private static RequestPostProcessor conRol(String oid, String... roles) {
        return jwt()
                .jwt(j -> j.claim("oid", oid).claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles)
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    @Test
    void sinTokenRespondeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/ordenes")).andExpect(status().isUnauthorized());
    }

    @Test
    void auditorNoPuedeCrearOrdenes() throws Exception {
        mockMvc.perform(post("/api/v1/ordenes").with(conRol("x", "Auditor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nuevaOrden(UUID.randomUUID()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void clienteSoloVeSusPropiasOrdenes() throws Exception {
        UUID clienteId = UUID.randomUUID();
        String body = mockMvc.perform(post("/api/v1/ordenes").with(conRol(clienteId.toString(), "Cliente"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nuevaOrden(clienteId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID ordenId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        // El cliente dueno (oid == clienteId) puede consultar
        mockMvc.perform(get("/api/v1/ordenes/{id}/clientes/{c}", ordenId, clienteId)
                        .with(conRol(clienteId.toString(), "Cliente")))
                .andExpect(status().isOk());

        // Otro cliente, aunque conozca el id de la orden y el clienteId, recibe 403
        mockMvc.perform(get("/api/v1/ordenes/{id}/clientes/{c}", ordenId, clienteId)
                        .with(conRol(UUID.randomUUID().toString(), "Cliente")))
                .andExpect(status().isForbidden());

        // Un Cliente no puede cambiar el estado ni listar todas las ordenes
        mockMvc.perform(post("/api/v1/ordenes/{id}/entrega", ordenId).with(conRol(clienteId.toString(), "Cliente")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ordenes").with(conRol(clienteId.toString(), "Cliente")))
                .andExpect(status().isForbidden());

        // El Jefe de taller si puede consultar por cualquier cliente
        mockMvc.perform(get("/api/v1/ordenes/{id}/clientes/{c}", ordenId, clienteId)
                        .with(conRol("jefe", "JefeTaller")))
                .andExpect(status().isOk());
    }

    private static CrearOrdenRequest nuevaOrden(UUID clienteId) {
        return new CrearOrdenRequest(UUID.randomUUID(), clienteId, "Ana Perez", "ana@correo.cl",
                "ABCD12", "Toyota", "Yaris", 2020);
    }
}
