package com.tallerpro.ms_tallerpro_jobs.admin.kafka;

import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TopicDetail;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TopicSummary;
import com.tallerpro.ms_tallerpro_jobs.service.CatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seguridad y contrato HTTP del administrador de Kafka con la validacion JWT activa. */
@SpringBootTest(properties = {
        "tallerpro.security.jwt-enabled=true",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KafkaAdminControllerTest {

    private static final String BASE = "/api/v1/admin/kafka";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaAdminService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalogClient;

    @SuppressWarnings("unchecked")
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private static RequestPostProcessor conRol(String... roles) {
        return jwt()
                .jwt(j -> j.claim("oid", "u-1").claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles)
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    @Test
    void sinTokenRespondeUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/topics")).andExpect(status().isUnauthorized());
    }

    @Test
    void rolDistintoDeAdminRespondeForbidden() throws Exception {
        for (String rol : List.of("JefeTaller", "Mecanico", "Auditor", "Cliente")) {
            mockMvc.perform(get(BASE + "/topics").with(conRol(rol))).andExpect(status().isForbidden());
        }
        verify(service, never()).listarTopicos(any(Boolean.class));
    }

    @Test
    void adminListaTopicos() throws Exception {
        when(service.listarTopicos(false)).thenReturn(List.of(new TopicSummary("jobs.events", 3, 1, false)));

        mockMvc.perform(get(BASE + "/topics").with(conRol("Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("jobs.events"))
                .andExpect(jsonPath("$[0].partitions").value(3));
    }

    @Test
    void adminCreaTopico() throws Exception {
        when(service.crearTopico(any())).thenReturn(new TopicDetail("pagos.events", false, 1, List.of(), Map.of()));

        mockMvc.perform(post(BASE + "/topics").with(conRol("Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"pagos.events","partitions":3,"replicationFactor":1,
                                 "configs":{"retention.ms":"604800000","cleanup.policy":"delete"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("pagos.events"));
    }

    @Test
    void crearTopicoConNombreInvalidoEsBadRequest() throws Exception {
        mockMvc.perform(post(BASE + "/topics").with(conRol("Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nombre invalido!\",\"partitions\":3,\"replicationFactor\":1}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).crearTopico(any());
    }

    @Test
    void crearTopicoConCeroParticionesEsBadRequest() throws Exception {
        mockMvc.perform(post(BASE + "/topics").with(conRol("Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok.topic\",\"partitions\":0,\"replicationFactor\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void erroresDelServicioSeTraducenAlCodigoHttp() throws Exception {
        doThrow(new KafkaAdminException(HttpStatus.CONFLICT, "usa force=true"))
                .when(service).eliminarTopico(eq("jobs.events"), eq(false));

        mockMvc.perform(delete(BASE + "/topics/jobs.events").with(conRol("Admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("usa force=true"));
    }

    @Test
    void eliminarTopicoRespondeNoContent() throws Exception {
        mockMvc.perform(delete(BASE + "/topics/pagos.events").with(conRol("Admin")))
                .andExpect(status().isNoContent());
        verify(service).eliminarTopico("pagos.events", false);
    }

    @Test
    void resetConEstrategiaDesconocidaEsBadRequest() throws Exception {
        mockMvc.perform(post(BASE + "/consumer-groups/ms-tallerpro-report/reset-offsets").with(conRol("Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"jobs.events\",\"strategy\":\"AYER\"}"))
                .andExpect(status().isBadRequest());
    }
}
