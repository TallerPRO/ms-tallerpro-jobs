package com.tallerpro.ms_tallerpro_jobs.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * SOLO para desarrollo local (tallerpro.security.jwt-enabled=false): inyecta un
 * usuario ficticio con los roles indicados en tallerpro.security.dev-roles para
 * que @PreAuthorize siga funcionando y se pueda probar la API sin tenant.
 * Nunca se registra cuando la validacion JWT esta activa.
 */
public class DevAuthenticationFilter extends OncePerRequestFilter {

    private final List<GrantedAuthority> authorities;

    public DevAuthenticationFilter(List<String> roles) {
        this.authorities = roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.trim()))
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            var auth = UsernamePasswordAuthenticationToken.authenticated("dev-user", null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
