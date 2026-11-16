package com.seatlock.gateway.filters;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Request IDs are tracing metadata, NOT an identity or authorization mechanism.
 * Sanitise untrusted client input; return the same ID to caller and downstream.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {
    public static final String HEADER = "X-Request-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && incoming.matches("[A-Za-z0-9._-]{1,64}")
            ? incoming
            : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate()
            .headers(h -> {
                h.set(HEADER, id);
                // Never allow clients to spoof an identity through this legacy Part 4 header.
                h.remove("X-Demo-User");
            }).build();
        exchange.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(exchange.mutate().request(request).build());
    }
}
