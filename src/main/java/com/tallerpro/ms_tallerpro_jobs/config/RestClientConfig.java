package com.tallerpro.ms_tallerpro_jobs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

/** Cliente HTTP hacia ms-tallerpro-catalog (T-02.04, T-02.09), con timeouts acotados. */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient catalogRestClient(JobsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.catalog().connectTimeoutMs());
        factory.setReadTimeout(properties.catalog().readTimeoutMs());
        return RestClient.builder()
                .baseUrl(properties.catalog().baseUrl())
                .requestFactory(factory)
                .requestInterceptor(propagarJwt())
                .build();
    }

    /**
     * ARQUITECTURA_ACCESO.md, seccion 6 ("Propagacion entre servicios"): las llamadas
     * internas reenvian el JWT ORIGINAL del usuario, y catalog lo valida por su cuenta.
     * En modo local sin JWT no hay token que propagar y la cabecera no se agrega.
     */
    private ClientHttpRequestInterceptor propagarJwt() {
        return (request, body, execution) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtAuth.getToken().getTokenValue());
            }
            return execution.execute(request, body);
        };
    }
}
