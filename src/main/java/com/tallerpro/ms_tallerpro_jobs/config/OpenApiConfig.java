package com.tallerpro.ms_tallerpro_jobs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T-02.13: documentacion de endpoints bajo el estandar OpenAPI/Swagger. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI jobsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TallerPro - ms-tallerpro-jobs API")
                .description("MS-01: nucleo transaccional de ordenes de servicio. CRUD, maquina de estados, "
                        + "asignacion de recursos y publicacion de eventos (Kafka/RabbitMQ) via patron Outbox.")
                .version("v1")
                .contact(new Contact().name("TallerPro").email("dev@tallerpro.cl")));
    }
}
