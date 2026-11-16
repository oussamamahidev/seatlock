package com.seatlock.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Entry-point policy. Downstream services still validate JWT and ownership. */
@Configuration
public class GatewaySecurityConfig {
    @Bean
    SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRolesConverter());
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/*/seats").permitAll()
                .pathMatchers(HttpMethod.POST, "/api/v1/bookings", "/api/v1/bookings/*/retry")
                    .hasAuthority("ROLE_customer")
                .pathMatchers(HttpMethod.GET, "/api/v1/bookings/*")
                    .hasAuthority("ROLE_customer")
                // Never publicly route machine-only Inventory writes or actuator internals.
                .anyExchange().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter))))
            .build();
    }
}
