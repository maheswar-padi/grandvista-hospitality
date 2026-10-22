package com.grandvista.payment.config;

import com.grandvista.platform.security.ProblemDetailSecurityHandlers;
import com.grandvista.platform.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailSecurityHandlers handlers,
            JwtAuthenticationConverter converter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // authenticated by HMAC signature in the controller, not by a user token
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/simulated").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/test-methods").permitAll()
                        .requestMatchers("/internal/**").hasRole(Roles.SERVICE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/**").hasAnyRole(Roles.ADMIN, Roles.STAFF)
                        .requestMatchers("/api/v1/admin/**").denyAll() // payment outcomes are never edited by people
                        .requestMatchers("/api/v1/payments/**").hasAnyRole(Roles.CUSTOMER, Roles.STAFF, Roles.ADMIN)
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .build();
    }
}
