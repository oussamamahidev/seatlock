package com.seatlock.booking.integration;

import com.seatlock.booking.application.BookingStore;
import com.seatlock.booking.application.RecoveryJobStore;
import com.seatlock.booking.application.RecoveryLease;
import com.seatlock.booking.application.InventoryPort;
import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.domain.BookingStatus;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired PlatformTransactionManager transactions;

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
                return jobs.claim(100, 90);
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

    @Test void onlyDueEligibleJobsAreClaimed() {
        var future = newBooking();
        jdbc.update("UPDATE booking_recovery_jobs SET next_attempt_at=now()+interval '1 hour' WHERE booking_id=?",
                future.id());
        var terminal = newBooking();
        store.transition(terminal.id(), BookingStatus.CONFIRMED, null);
        var due = newBooking();
        makeDue(due.id());

        var claimed = jobs.claim(100, 90);

        assertThat(claimed).extracting(RecoveryLease::bookingId)
                .contains(due.id()).doesNotContain(future.id(), terminal.id());
        assertThat(state(future.id())).isEqualTo("READY");
        assertThat(attempts(future.id())).isZero();
        assertThat(state(terminal.id())).isEqualTo("DONE");
    }

    @Test void expiredLeaseIsReclaimedAndStaleWorkerCannotChangeBookingOrJob() {
        var booking = newBooking();
        store.transition(booking.id(), BookingStatus.UNCERTAIN, "Remote outcome unknown");
        makeDue(booking.id());
        var stale = claim(booking.id());
        assertThat(stale.attempt()).isEqualTo(1);
        jdbc.update("UPDATE booking_recovery_jobs SET lease_until=now()-interval '1 second' WHERE booking_id=?",
                booking.id());

        var current = claim(booking.id());
        assertThat(current.token()).isNotEqualTo(stale.token());
        assertThat(current.attempt()).isEqualTo(2);
        assertThat(jobs.ownsLease(stale)).isFalse();
        assertThat(jobs.ownsLease(current)).isTrue();

        store.settleRecovery(stale, new InventoryPort.Attempt(BookingStatus.REJECTED, "stale"), 12);
        assertThat(store.internalBooking(booking.id()).status()).isEqualTo(BookingStatus.UNCERTAIN);
        assertThat(state(booking.id())).isEqualTo("LEASED");
        assertThat(jobs.ownsLease(current)).isTrue();

        store.settleRecovery(current, new InventoryPort.Attempt(BookingStatus.CONFIRMED, null), 12);
        store.settleRecovery(stale, new InventoryPort.Attempt(BookingStatus.REJECTED, "late"), 12);
        assertThat(store.internalBooking(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(state(booking.id())).isEqualTo("DONE");
        assertThat(attempts(booking.id())).isEqualTo(2);
    }

    @Test void expiredLeaseWithoutNewOwnerCannotSettle() {
        var booking = newBooking();
        makeDue(booking.id());
        var expired = claim(booking.id());
        jdbc.update("UPDATE booking_recovery_jobs SET lease_until=now()-interval '1 second' WHERE booking_id=?",
                booking.id());

        store.settleRecovery(expired, new InventoryPort.Attempt(BookingStatus.CONFIRMED, null), 12);

        assertThat(store.internalBooking(booking.id()).status()).isEqualTo(BookingStatus.PENDING);
        assertThat(state(booking.id())).isEqualTo("LEASED");
        assertThat(claim(booking.id()).attempt()).isEqualTo(2);
    }

    @Test void leaseExpiringWhileWaitingForBookingLockCannotCommitAStaleResult() throws Exception {
        var booking = newBooking();
        makeDue(booking.id());
        var lease = claim(booking.id());
        var executor = Executors.newSingleThreadExecutor();
        var backend = new java.util.concurrent.atomic.AtomicInteger();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM bookings WHERE id=? FOR UPDATE")) {
                statement.setObject(1, booking.id());
                statement.executeQuery().close();
            }
            try {
                var result = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return store.settleRecovery(lease,
                            new InventoryPort.Attempt(BookingStatus.CONFIRMED, null), 12);
                }));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
                        backend.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?",
                                Boolean.class, backend.get())));
                // Expire only this synthetic lease after the waiting transaction has begun.
                jdbc.update("UPDATE booking_recovery_jobs SET lease_until=clock_timestamp() WHERE booking_id=?",
                        booking.id());
                connection.rollback();
                assertThat(result.get(3, TimeUnit.SECONDS).status()).isEqualTo(BookingStatus.PENDING);
                assertThat(state(booking.id())).isEqualTo("LEASED");
            } finally {
                connection.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void claimSkipsRowsLockedByAnotherTransactionWithoutWaiting() throws Exception {
        var held = newBooking();
        var available = newBooking();
        makeDue(held.id());
        makeDue(available.id());
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT booking_id FROM booking_recovery_jobs WHERE booking_id=? FOR UPDATE")) {
                statement.setObject(1, held.id());
                statement.executeQuery().close();
            }
            try {
                var claimed = executor.submit(() -> jobs.claim(100, 90)).get(2, TimeUnit.SECONDS);
                assertThat(claimed).extracting(RecoveryLease::bookingId)
                        .contains(available.id()).doesNotContain(held.id());
            } finally {
                connection.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(claim(held.id()).attempt()).isEqualTo(1);
    }

    @Test void unknownOutcomeUsesBackoffAndExhaustionKeepsBookingUncertain() {
        var booking = newBooking();
        makeDue(booking.id());
        var first = claim(booking.id());
        Instant before = Instant.now();
        store.settleRecovery(first, new InventoryPort.Attempt(BookingStatus.UNCERTAIN, "timeout"), 2);
        Instant after = Instant.now();

        assertThat(state(booking.id())).isEqualTo("READY");
        assertThat(attempts(booking.id())).isEqualTo(1);
        Instant next = jdbc.queryForObject("SELECT next_attempt_at FROM booking_recovery_jobs WHERE booking_id=?",
                Timestamp.class, booking.id()).toInstant();
        assertThat(next).isBetween(before.plusSeconds(5), after.plusSeconds(6));
        assertThat(jobs.ownsLease(first)).isFalse();

        makeDue(booking.id());
        var second = claim(booking.id());
        assertThat(second.attempt()).isEqualTo(2);
        store.settleRecovery(second, new InventoryPort.Attempt(BookingStatus.UNCERTAIN, "timeout"), 2);
        assertThat(state(booking.id())).isEqualTo("EXHAUSTED");
        assertThat(store.internalBooking(booking.id()).status()).isEqualTo(BookingStatus.UNCERTAIN);
        assertThat(jdbc.queryForObject("SELECT last_error FROM booking_recovery_jobs WHERE booking_id=?",
                String.class, booking.id())).contains("manual retry required");
        makeDue(booking.id());
        assertThat(jobs.claim(100, 90)).extracting(RecoveryLease::bookingId).doesNotContain(booking.id());
    }

    @Test void terminalOutcomesCommitBookingAndJobTogether() {
        for (BookingStatus terminal : new BookingStatus[]{BookingStatus.CONFIRMED, BookingStatus.REJECTED}) {
            var booking = newBooking();
            makeDue(booking.id());
            var lease = claim(booking.id());
            store.settleRecovery(lease, new InventoryPort.Attempt(terminal, null), 12);
            assertThat(store.internalBooking(booking.id()).status()).isEqualTo(terminal);
            assertThat(state(booking.id())).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT owner_token IS NULL AND lease_until IS NULL FROM booking_recovery_jobs WHERE booking_id=?",
                    Boolean.class, booking.id())).isTrue();
        }
    }

    private BookingView newBooking() {
        UUID event = UUID.randomUUID(), seat = UUID.randomUUID();
        return store.insert(UUID.randomUUID(), "job-" + UUID.randomUUID(), event, seat, event + ":" + seat);
    }

    private void makeDue(UUID id) {
        // Only synthetic Testcontainers fixtures are adjusted; never touches the local databases.
        jdbc.update("UPDATE booking_recovery_jobs SET next_attempt_at=now()-interval '1 second' WHERE booking_id=?", id);
    }

    private RecoveryLease claim(UUID id) {
        return jobs.claim(100, 90).stream().filter(lease -> lease.bookingId().equals(id))
                .findFirst().orElseThrow();
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT state FROM booking_recovery_jobs WHERE booking_id=?", String.class, id);
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempt_count FROM booking_recovery_jobs WHERE booking_id=?", Integer.class, id);
    }
}
