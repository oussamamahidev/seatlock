package com.seatlock.booking.integration;

import com.seatlock.booking.application.BookingStore;
import com.seatlock.booking.application.BookingCancellationConflictException;
import com.seatlock.booking.application.CancellationJobStore;
import com.seatlock.booking.application.CancellationLease;
import com.seatlock.booking.domain.BookingStatus;
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
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"seatlock.recovery.enabled=false",
        "seatlock.cancellation.enabled=false", "security.machine.client-secret=test-secret"})
class BookingCancellationIntegrationTest {
    @Container
    static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("booking_cancellation_test");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",postgres::getJdbcUrl);
        props.add("spring.datasource.username",postgres::getUsername);
        props.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired BookingStore store;
    @Autowired CancellationJobStore jobs;
    @Autowired JdbcTemplate jdbc;

    private ConfirmedBooking confirmed() {
        UUID user=UUID.randomUUID(), event=UUID.randomUUID(), seat=UUID.randomUUID();
        var pending=store.insert(user,"cancel-"+UUID.randomUUID(),event,seat,event+":"+seat);
        store.transition(pending.id(),BookingStatus.CONFIRMED,null);
        return new ConfirmedBooking(user,pending.id());
    }

    @Test
    void cancellationCreatesDurableJobInSameTransactionAndCompletesWithLease() {
        var booking=confirmed();
        var pending=store.beginCancellation(booking.customer(), booking.id());
        assertThat(pending.status()).isEqualTo(BookingStatus.CANCEL_PENDING);
        assertThat(jdbc.queryForObject("SELECT state FROM booking_cancellation_jobs WHERE booking_id=?",
                String.class,booking.id())).isEqualTo("READY");
        var claimed=jobs.claim(5,90).stream()
                .filter(j->j.bookingId().equals(booking.id())).findFirst().orElseThrow();
        var canceled=store.settleCancellation(claimed,true,12);
        assertThat(canceled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT state FROM booking_cancellation_jobs WHERE booking_id=?",
                String.class,booking.id())).isEqualTo("DONE");
        assertThat(store.beginCancellation(booking.customer(), booking.id()).status())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThatThrownBy(() -> store.beginCancellation(UUID.randomUUID(), booking.id()))
                .isInstanceOf(com.seatlock.booking.application.BookingNotFoundException.class);
    }

    @Test
    void concurrentWorkersCannotClaimSameCancellationJob() throws Exception {
        var booking=confirmed();
        store.beginCancellation(booking.customer(),booking.id());
        var exec=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            Callable<java.util.List<CancellationLease>> command=()->{start.await();return jobs.claim(1,90);};
            var first=exec.submit(command);
            var second=exec.submit(command);
            start.countDown();
            var all=new java.util.ArrayList<CancellationLease>();
            all.addAll(first.get(15,TimeUnit.SECONDS));
            all.addAll(second.get(15,TimeUnit.SECONDS));
            assertThat(all.stream().filter(x->x.bookingId().equals(booking.id())).count())
                    .isEqualTo(1);
        } finally {start.countDown();exec.shutdownNow();}
    }

    @Test
    void staleLeaseCannotMarkBookingCanceled() {
        var booking=confirmed();
        store.beginCancellation(booking.customer(),booking.id());
        var lease=jobs.claim(1,90).getFirst();
        jdbc.update("UPDATE booking_cancellation_jobs SET owner_token=? WHERE booking_id=?",
                UUID.randomUUID(),booking.id());
        assertThat(store.settleCancellation(lease,true,12).status())
                .isEqualTo(BookingStatus.CANCEL_PENDING);
        assertThat(jdbc.queryForObject("SELECT state FROM booking_cancellation_jobs WHERE booking_id=?",
                String.class,booking.id())).isEqualTo("LEASED");
    }

    record ConfirmedBooking(UUID customer,UUID id) { }
}
