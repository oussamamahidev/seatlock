package com.seatlock.inventory.integration;

import com.seatlock.inventory.application.ReservationConflictException;
import com.seatlock.inventory.application.SeatHoldConflictException;
import com.seatlock.inventory.application.SeatHoldNotFoundException;
import com.seatlock.inventory.application.SeatHoldService;
import com.seatlock.inventory.application.SeatReservationService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {"spring.flyway.locations=classpath:db/migration", "seatlock.holds.ttl-seconds=60", "seatlock.holds.enabled=false"})
class SeatHoldLifecycleIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("seatlock_hold_test");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired SeatHoldService holds;
    @Autowired SeatReservationService reservations;
    @Autowired JdbcTemplate jdbc;
    UUID event, seat, alice, bob;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM seat_hold_requests");
        jdbc.update("DELETE FROM seat_reservation_history");
        jdbc.update("DELETE FROM seats");
        jdbc.update("DELETE FROM events");
        event = UUID.randomUUID(); seat = UUID.randomUUID();
        alice = UUID.randomUUID(); bob = UUID.randomUUID();
        jdbc.update("INSERT INTO events(id,title,venue,starts_at) VALUES(?,'test','test',now())", event);
        jdbc.update("INSERT INTO seats(id,event_id,label,status) VALUES(?,?,'A01','AVAILABLE')", seat,event);
    }

    @Test
    void activeHoldBlocksOtherCustomerAndCanBeRedeemedByOwner() {
        UUID hold = UUID.randomUUID(), booking = UUID.randomUUID();
        var result = holds.create(event, seat, hold, alice);
        assertThat(result.created()).isTrue();
        assertThat(result.receipt().status()).isEqualTo("HELD");
        assertThat(holds.create(event, seat, hold, alice).created()).isFalse();

        assertThatThrownBy(() -> reservations.reserve(event, seat, UUID.randomUUID()))
                .isInstanceOf(ReservationConflictException.class);
        assertThatThrownBy(() -> reservations.reserve(event, seat, UUID.randomUUID(), hold, bob))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(reservations.reserve(event, seat, booking, hold, alice).created()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT hold_id FROM seats WHERE id=?", UUID.class, seat))
                .isNull();
        // Once CONFIRMED, hold cleanup must NEVER release it.
        assertThat(holds.expireDue()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat))
                .isEqualTo("RESERVED");
    }

    @Test
    void expiredHoldCanBeReclaimedAndOldHoldCannotBeReplayed() {
        UUID hold = UUID.randomUUID();
        holds.create(event, seat, hold, alice);
        jdbc.update("UPDATE seats SET hold_expires_at=now()-interval '1 minute' WHERE id=?", seat);
        assertThat(reservations.reserve(event, seat, UUID.randomUUID()).created()).isTrue();
        assertThatThrownBy(() -> holds.create(event, seat, hold, alice))
                .isInstanceOf(SeatHoldConflictException.class);
    }

    @Test
    void explicitCancellationIsOwnedAndDoesNotRebindAnOldIdempotencyKey() {
        UUID hold = UUID.randomUUID();
        holds.create(event, seat, hold, alice);
        assertThatThrownBy(() -> holds.cancel(event, seat, hold, bob))
                .isInstanceOf(SeatHoldNotFoundException.class);
        holds.cancel(event, seat, hold, alice);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat))
                .isEqualTo("AVAILABLE");
        assertThatThrownBy(() -> holds.create(event, seat, hold, alice))
                .isInstanceOf(SeatHoldConflictException.class);
        assertThat(holds.create(event, seat, UUID.randomUUID(), bob).created()).isTrue();
    }

    @Test
    void sweepReleasesOnlyExpiredHolds() {
        UUID hold = UUID.randomUUID();
        holds.create(event, seat, hold, alice);
        assertThat(holds.expireDue()).isZero();
        jdbc.update("UPDATE seats SET hold_expires_at=now()-interval '1 minute' WHERE id=?", seat);
        assertThat(holds.expireDue()).isEqualTo(1);
        assertThat(holds.expireDue()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void cancelingConfirmedReservationCreatesPermanentTombstone() {
        UUID original=UUID.randomUUID();
        assertThat(reservations.reserve(event,seat,original).created()).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM seat_reservation_history WHERE booking_id=?",
                String.class,original)).isEqualTo("ACTIVE");
        reservations.release(event,seat,original);
        reservations.release(event,seat,original); // idempotent
        assertThat(jdbc.queryForObject("SELECT state FROM seat_reservation_history WHERE booking_id=?",
                String.class,original)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> reservations.reserve(event,seat,original))
                .isInstanceOf(ReservationConflictException.class);
        UUID newBooking=UUID.randomUUID();
        assertThat(reservations.reserve(event,seat,newBooking).created()).isTrue();
        reservations.release(event,seat,original); // stale release MUST NOT touch new owner's seat
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM seats WHERE id=?",UUID.class,seat))
                .isEqualTo(newBooking);
    }

    @Test
    void competingHoldsHaveAtMostOneWinner() throws Exception {
        int concurrent = 20;
        var pool = Executors.newFixedThreadPool(concurrent);
        var gate = new CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i=0; i<concurrent; i++) {
                final UUID owner = UUID.randomUUID(), token = UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    gate.await();
                    try { return holds.create(event, seat, token, owner).created(); }
                    catch (SeatHoldConflictException conflict) { return false; }
                }));
            }
            gate.countDown();
            int winners=0;
            for (var future: futures) if (future.get(30,TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE id=? AND status='HELD'", Integer.class,seat))
                    .isEqualTo(1);
        } finally { gate.countDown(); pool.shutdownNow(); }
    }
}
