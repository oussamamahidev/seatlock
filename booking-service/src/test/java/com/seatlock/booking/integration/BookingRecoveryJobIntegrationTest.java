package com.seatlock.booking.integration;

import com.seatlock.booking.application.BookingStore;
import com.seatlock.booking.application.RecoveryJobStore;
import com.seatlock.booking.domain.BookingStatus;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {"seatlock.recovery.enabled=false", "security.machine.client-secret=test-secret"})
class BookingRecoveryJobIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("booking_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired BookingStore store;
    @Autowired RecoveryJobStore jobs;
    @Autowired JdbcTemplate jdbc;

    @Test void insertionAndRecoveryJobCommitTogether() {
        var id = UUID.randomUUID();
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var booking = store.insert(id, "recover-" + UUID.randomUUID(), event, seat, event + ":" + seat);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_recovery_jobs WHERE booking_id = ?",
                Integer.class, booking.id())).isEqualTo(1);
        store.transition(booking.id(), BookingStatus.CONFIRMED, null);
        assertThat(jdbc.queryForObject("SELECT state FROM booking_recovery_jobs WHERE booking_id = ?",
                String.class, booking.id())).isEqualTo("DONE");
    }

    @Test void skipLockedPreventsDuplicateLease() throws Exception {
        var customer = UUID.randomUUID();
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var booking = store.insert(customer, "lease-" + UUID.randomUUID(), event, seat, event + ":" + seat);
        // Make this synthetic job due immediately.
        jdbc.update("UPDATE booking_recovery_jobs SET next_attempt_at = now() - interval '1 second' WHERE booking_id = ?",
                booking.id());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<java.util.List<com.seatlock.booking.application.RecoveryLease>> run = () -> {
                gate.await();
                return jobs.claim(1, 90);
            };
            Future<java.util.List<com.seatlock.booking.application.RecoveryLease>> a = executor.submit(run);
            Future<java.util.List<com.seatlock.booking.application.RecoveryLease>> b = executor.submit(run);
            gate.countDown();
            var combined = new java.util.ArrayList<com.seatlock.booking.application.RecoveryLease>();
            combined.addAll(a.get(10, TimeUnit.SECONDS));
            combined.addAll(b.get(10, TimeUnit.SECONDS));
            assertThat(combined.stream().filter(j -> j.bookingId().equals(booking.id())).count())
                    .isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
}
