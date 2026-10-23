package com.grandvista.room.config;

import com.grandvista.platform.security.ProblemDetailSecurityHandlers;
import com.grandvista.platform.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authorization is enforced here independently of the gateway: catalogue reads are public, inventory
 * administration needs STAFF/ADMIN, and the internal hold API accepts only service tokens.
 */
@Configuration
@EnableMethodSecurity
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
                        .requestMatchers(HttpMethod.GET, "/api/v1/properties", "/api/v1/properties/**", "/api/v1/rooms/**", "/api/v1/room-types/**",
                                "/api/v1/amenities/**", "/api/v1/media/**").permitAll()
                        .requestMatchers("/internal/**").hasRole(Roles.SERVICE)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/rooms/*/status").hasAnyRole(Roles.ADMIN, Roles.STAFF)
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/**").hasAnyRole(Roles.ADMIN, Roles.STAFF)
                        .requestMatchers("/api/v1/admin/**").hasRole(Roles.ADMIN)
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
