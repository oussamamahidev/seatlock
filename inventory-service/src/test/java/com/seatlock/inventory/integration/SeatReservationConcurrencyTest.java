package com.seatlock.inventory.integration;

import com.seatlock.inventory.application.ReservationConflictException;
import com.seatlock.inventory.application.ReservationResult;
import com.seatlock.inventory.application.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration")
class SeatReservationConcurrencyTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("seatlock_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired SeatReservationService service;
    @Autowired JdbcTemplate jdbc;

    private UUID eventId;
    private UUID seatId;

    @BeforeEach
    void prepare() {
        // Testcontainers owns an isolated, disposable database.
        jdbc.update("DELETE FROM seats");
        jdbc.update("DELETE FROM events");
        eventId = UUID.randomUUID();
        seatId = UUID.randomUUID();
        jdbc.update("INSERT INTO events (id, title, venue, starts_at) VALUES (?, 'Test Event', 'Test Hall', now())", eventId);
        jdbc.update("INSERT INTO seats (id, event_id, label, status) VALUES (?, ?, 'A01', 'AVAILABLE')", seatId, eventId);
    }

    @Test
    void twentyConcurrentBookingsProduceExactlyOneWinner() throws Exception {
        int clients = 20;
        var ready = new CountDownLatch(clients);
        var start = new CountDownLatch(1);
        var responses = new ArrayList<Future<Boolean>>();
        var executor = Executors.newFixedThreadPool(clients);

        try {
            for (int i = 0; i < clients; i++) {
                UUID bookingId = UUID.randomUUID();
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timeout");
                    try {
                        ReservationResult result = service.reserve(eventId, seatId, bookingId);
                        return result.created();
                    } catch (ReservationConflictException conflict) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int winners = 0;
            for (var response : responses) {
                if (response.get(30, TimeUnit.SECONDS)) winners++;
            }
            assertThat(winners).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM seats WHERE id = ? AND status='RESERVED' AND reservation_id IS NOT NULL",
                    Integer.class, seatId)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retrySameBookingIdIsIdempotent() {
        UUID bookingId = UUID.randomUUID();
        assertThat(service.reserve(eventId, seatId, bookingId).created()).isTrue();
        assertThat(service.reserve(eventId, seatId, bookingId).created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM seats WHERE id=?", UUID.class, seatId))
                .isEqualTo(bookingId);
    }

    @Test
    void databasePreventsSameBookingIdFromOwningTwoSeats() {
        UUID bookingId = UUID.randomUUID();
        UUID secondSeatId = UUID.randomUUID();
        jdbc.update("INSERT INTO seats (id, event_id, label, status) VALUES (?, ?, 'A02', 'AVAILABLE')", secondSeatId, eventId);
        assertThat(service.reserve(eventId, seatId, bookingId).created()).isTrue();
        assertThatThrownBy(() -> service.reserve(eventId, secondSeatId, bookingId))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, secondSeatId))
                .isEqualTo("AVAILABLE");
    }
}
