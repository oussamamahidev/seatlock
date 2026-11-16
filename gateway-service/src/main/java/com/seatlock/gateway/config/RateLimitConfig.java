package com.seatlock.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional Redis token bucket: per *authenticated subject*, not a spoofable header. */
@Configuration
public class RateLimitConfig {
    @Bean("customerSubjectKeyResolver")
    KeyResolver customerSubjectKeyResolver() {
        return exchange -> exchange.getPrincipal().map(java.security.Principal::getName);
    }
}
