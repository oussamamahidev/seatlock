package com.seatlock.booking.integration;

import com.seatlock.booking.application.BookingStore;
import com.seatlock.booking.application.InventoryPort;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.booking.infrastructure.RestInventoryAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Runs the real Spring scheduler, transaction proxies, and PostgreSQL claim SQL. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "security.machine.client-secret=test-secret",
        "seatlock.recovery.enabled=true",
        "seatlock.recovery.initial-delay-ms=50",
        "seatlock.recovery.poll-interval-ms=50"
})
class BookingRecoverySchedulerIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("booking_scheduler_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired BookingStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    @MockitoBean RestInventoryAdapter inventory;

    @AfterAll
    static void stopScheduledTasksBeforePostgresStops(@Autowired ScheduledAnnotationBeanPostProcessor tasks) {
        tasks.destroy();
    }

    @Test
    void schedulerClaimsDueJobWithSameBookingIdAndFinishesItWithoutManualPoll() {
        when(inventory.reserve(any())).thenReturn(
                new InventoryPort.Attempt(BookingStatus.CONFIRMED, null));
        var customer = UUID.randomUUID();
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var future = store.insert(customer, "future-" + UUID.randomUUID(), event, seat, event + ":" + seat);
        jdbc.update("UPDATE booking_recovery_jobs SET next_attempt_at=now()+interval '1 hour' WHERE booking_id=?",
                future.id());
        var due = store.insert(customer, "scheduled-" + UUID.randomUUID(), event, seat, event + ":" + seat);
        store.transition(due.id(), BookingStatus.UNCERTAIN, "Inventory was unavailable");
        jdbc.update("UPDATE booking_recovery_jobs SET next_attempt_at=now()-interval '1 second' WHERE booking_id=?",
                due.id());

        // Never invoke poll() or /retry: only Spring's scheduled task can satisfy this.
        await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            assertThat(store.internalBooking(due.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(jdbc.queryForObject("SELECT state FROM booking_recovery_jobs WHERE booking_id=?",
                    String.class, due.id())).isEqualTo("DONE");
            assertThat(metrics.get("seatlock.recovery.outcomes").tag("status", "CONFIRMED")
                    .counter().count()).isEqualTo(1.0);
        });
        verify(inventory, times(1)).reserve(argThat(booking -> booking.id().equals(due.id())));
        verify(inventory, never()).reserve(argThat(booking -> booking.id().equals(future.id())));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM booking_recovery_jobs WHERE booking_id=?",
                Integer.class, due.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM booking_recovery_jobs WHERE booking_id=?",
                Integer.class, future.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT owner_token IS NULL AND lease_until IS NULL FROM booking_recovery_jobs WHERE booking_id=?",
                Boolean.class, due.id())).isTrue();
        assertThat(metrics.get("seatlock.recovery.polls").counter().count()).isPositive();
        assertThat(metrics.get("seatlock.recovery.claimed").counter().count()).isEqualTo(1);
        assertThat(metrics.get("seatlock.recovery.claim.errors").counter().count()).isZero();
    }
}
