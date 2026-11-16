package com.seatlock.booking.application;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RetryBackoffTest {
    private final RetryBackoff backoff = new RetryBackoff();
    private final UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test void increasingAndCappedWithJitter() {
        assertThat(backoff.delay(id, 1)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(6));
        assertThat(backoff.delay(id, 2)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(11));
        assertThat(backoff.delay(id, 7)).isBetween(Duration.ofSeconds(120), Duration.ofSeconds(121));
        assertThat(backoff.delay(id, Integer.MAX_VALUE))
                .isBetween(Duration.ofSeconds(120), Duration.ofSeconds(121));
    }

    @Test void invalidAttemptFails() {
        assertThatThrownBy(() -> backoff.delay(id, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
