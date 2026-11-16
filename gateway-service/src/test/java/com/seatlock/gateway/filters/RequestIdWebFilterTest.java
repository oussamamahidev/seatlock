package com.seatlock.gateway.filters;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdWebFilterTest {
    private final RequestIdWebFilter filter = new RequestIdWebFilter();

    @Test
    void preservesSafeRequestIdAndRemovesSpoofedUserHeader() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events")
            .header("X-Request-ID", "my-request_123")
            .header("X-Demo-User", "forged-customer"));
        AtomicReference<String> forwarded = new AtomicReference<>();
        AtomicReference<String> spoof = new AtomicReference<>();
        filter.filter(exchange, forwardedExchange -> {
            forwarded.set(forwardedExchange.getRequest().getHeaders().getFirst("X-Request-ID"));
            spoof.set(forwardedExchange.getRequest().getHeaders().getFirst("X-Demo-User"));
            return Mono.empty();
        }).block();
        assertThat(forwarded.get()).isEqualTo("my-request_123");
        assertThat(spoof.get()).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-ID"))
            .isEqualTo("my-request_123");
    }

    @Test
    void rejectsUnsafeIdAndGeneratesUuid() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events")
            .header("X-Request-ID", "bad value with spaces"));
        AtomicReference<String> forwarded = new AtomicReference<>();
        filter.filter(exchange, updated -> {
            forwarded.set(updated.getRequest().getHeaders().getFirst("X-Request-ID"));
            return Mono.empty();
        }).block();
        assertThat(forwarded.get()).matches("[0-9a-f-]{36}");
    }
}
