package com.tallerpro.ms_tallerpro_jobs.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.util.List;

/**
 * Capa 3 del control de acceso (ARQUITECTURA_ACCESO.md, seccion 6): ms-tallerpro-jobs
 * vuelve a validar el JWT propagado por el gateway (confianza cero) y autoriza por rol
 * con @PreAuthorize en OrdenServicioController. Rol insuficiente -> 403.
 *
 * TALLERPRO_JWT_ENABLED=false (solo desarrollo local sin tenant) inyecta un usuario
 * ficticio con los roles de TALLERPRO_DEV_ROLES.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    @ConditionalOnProperty(prefix = "tallerpro.security", name = "jwt-enabled", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tallerpro.security", name = "jwt-enabled", havingValue = "false")
    SecurityFilterChain openFilterChain(HttpSecurity http,
                                        @Value("${tallerpro.security.dev-roles:Admin,JefeTaller}") List<String> devRoles)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new DevAuthenticationFilter(devRoles), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** App roles de Azure AD (claim "roles") -> ROLE_Admin, ROLE_JefeTaller, ROLE_Cliente, ... */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
