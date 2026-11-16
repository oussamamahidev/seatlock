package com.seatlock.booking.integration;

import com.seatlock.booking.application.BookingStore;
import com.seatlock.booking.domain.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {"seatlock.recovery.enabled=false", "security.machine.client-secret=test-secret"})
class BookingPersistenceTest {
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
    @Autowired JdbcTemplate jdbc;

    @Test
    void uniqueIndexPreventsDuplicateKeyAndOriginalRowSurvives() {
        UUID customer = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        String key = "unique-" + UUID.randomUUID();
        String fp = event + ":" + seat;

        var original = store.insert(customer, key, event, seat, fp);
        assertThat(original.status()).isEqualTo(BookingStatus.PENDING);

        assertThatThrownBy(() -> store.insert(customer, key, event, seat, fp))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE customer_id = ? AND idempotency_key = ?",
                Integer.class, customer, key)).isEqualTo(1);
    }

    @Test
    void statusTransitionsPersistAfterCommit() {
        UUID customer = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        var booking = store.insert(customer, "transition-" + UUID.randomUUID(),
                event, seat, event + ":" + seat);
        store.transition(booking.id(), BookingStatus.UNCERTAIN, "temporary failure");
        assertThat(store.ownedBooking(customer, booking.id()).status())
                .isEqualTo(BookingStatus.UNCERTAIN);
        store.transition(booking.id(), BookingStatus.CONFIRMED, null);
        assertThat(store.ownedBooking(customer, booking.id()).status())
                .isEqualTo(BookingStatus.CONFIRMED);
        // Late transient failure must not downgrade a terminal decision.
        store.transition(booking.id(), BookingStatus.UNCERTAIN, "late response");
        assertThat(store.ownedBooking(customer, booking.id()).status())
                .isEqualTo(BookingStatus.CONFIRMED);
    }
}
