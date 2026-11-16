package com.seatlock.booking.application;

import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Exponential backoff with stable per-job jitter, capped to prevent overflow. */
@Component
public class RetryBackoff {
    public Duration delay(UUID bookingId, int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        long seconds = Math.min(120L, 5L * (1L << Math.min(attempt - 1, 5)));
        long jitterMillis = Math.floorMod(bookingId.getLeastSignificantBits() ^ attempt, 1000L);
        return Duration.ofMillis(seconds * 1000L + jitterMillis);
    }
}
