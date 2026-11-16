package com.seatlock.booking.application;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.domain.BookingStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingRecoveryWorkerTest {
    @Mock RecoveryJobStore jobs;
    @Mock BookingStore store;
    @Mock InventoryPort inventory;

    @Test void idleWorkerPublishesZeroCountersAndRecordsEmptyPoll() {
        var registry = new SimpleMeterRegistry();
        var worker = new BookingRecoveryWorker(jobs, store, inventory, registry, 5, 90, 12);
        assertThat(registry.get("seatlock.recovery.polls").counter().count()).isZero();
        when(jobs.claim(5, 90)).thenReturn(List.of());

        worker.poll();

        assertThat(registry.get("seatlock.recovery.polls").counter().count()).isEqualTo(1);
        assertThat(registry.get("seatlock.recovery.claimed").counter().count()).isZero();
        assertThat(registry.get("seatlock.recovery.duration").timer().count()).isZero();
        verifyNoInteractions(store, inventory);
    }

    @Test void claimFailureIsObservableAndNextPollCanRetry() {
        var registry = new SimpleMeterRegistry();
        var worker = new BookingRecoveryWorker(jobs, store, inventory, registry, 5, 90, 12);
        when(jobs.claim(5, 90)).thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of());

        worker.poll();
        worker.poll();

        assertThat(registry.get("seatlock.recovery.claim.errors").counter().count()).isEqualTo(1);
        assertThat(registry.get("seatlock.recovery.polls").counter().count()).isEqualTo(2);
        verifyNoInteractions(store, inventory);
    }

    @Test void oneProcessingFailureDoesNotStopTheRestOfTheClaimedBatch() {
        var failed = new RecoveryLease(UUID.randomUUID(), UUID.randomUUID(), 1);
        var finished = new RecoveryLease(UUID.randomUUID(), UUID.randomUUID(), 2);
        var terminal = booking(finished.bookingId(), BookingStatus.CONFIRMED);
        var registry = new SimpleMeterRegistry();
        var worker = new BookingRecoveryWorker(jobs, store, inventory, registry, 5, 90, 12);
        when(jobs.claim(5, 90)).thenReturn(List.of(failed, finished));
        when(store.internalBooking(failed.bookingId())).thenThrow(new IllegalStateException("database unavailable"));
        when(store.internalBooking(finished.bookingId())).thenReturn(terminal);
        when(store.settleRecovery(eq(finished), any(), eq(12))).thenReturn(terminal);

        worker.poll();

        assertThat(registry.get("seatlock.recovery.errors").counter().count()).isEqualTo(1);
        assertThat(registry.get("seatlock.recovery.claimed").counter().count()).isEqualTo(2);
        assertThat(registry.get("seatlock.recovery.duration").timer().count()).isEqualTo(2);
        assertThat(registry.get("seatlock.recovery.outcomes").tag("status", "CONFIRMED")
                .counter().count()).isEqualTo(1);
        verify(store, never()).settleRecovery(eq(failed), any(), anyInt());
        verifyNoInteractions(inventory);
    }

    @Test void unknownJobRetriedWithSameBookingIdAndObserved() {
        var id = UUID.randomUUID();
        var lease = new RecoveryLease(id, UUID.randomUUID(), 1);
        var pending = booking(id, BookingStatus.UNCERTAIN);
        var confirmed = booking(id, BookingStatus.CONFIRMED);
        var registry = new SimpleMeterRegistry();
        var worker = new BookingRecoveryWorker(jobs, store, inventory, registry, 5, 90, 12);

        when(jobs.claim(5, 90)).thenReturn(List.of(lease));
        when(store.internalBooking(id)).thenReturn(pending);
        var result = new InventoryPort.Attempt(BookingStatus.CONFIRMED, null);
        when(inventory.reserve(pending)).thenReturn(result);
        when(store.settleRecovery(lease, result, 12)).thenReturn(confirmed);

        worker.poll();

        verify(inventory).reserve(pending);
        verify(store).settleRecovery(lease, result, 12);
        assertThat(registry.counter("seatlock.recovery.outcomes", "status", "CONFIRMED").count())
                .isEqualTo(1.0);
    }

    @Test void networkFailureBecomesUncertainNotFalseRejection() {
        var id = UUID.randomUUID();
        var lease = new RecoveryLease(id, UUID.randomUUID(), 3);
        var pending = booking(id, BookingStatus.PENDING);
        var uncertain = booking(id, BookingStatus.UNCERTAIN);
        var registry = new SimpleMeterRegistry();
        var worker = new BookingRecoveryWorker(jobs, store, inventory, registry, 5, 90, 12);

        when(jobs.claim(5, 90)).thenReturn(List.of(lease));
        when(store.internalBooking(id)).thenReturn(pending);
        when(inventory.reserve(pending)).thenThrow(new IllegalStateException("Keycloak is down"));
        when(store.settleRecovery(eq(lease),
                argThat(a -> a.status() == BookingStatus.UNCERTAIN), eq(12)))
                .thenReturn(uncertain);

        worker.poll();

        verify(store).settleRecovery(eq(lease),
                argThat(a -> a.status() == BookingStatus.UNCERTAIN), eq(12));
        assertThat(registry.counter("seatlock.recovery.outcomes", "status", "UNCERTAIN").count())
                .isEqualTo(1.0);
    }

    private static BookingView booking(UUID id, BookingStatus status) {
        return new BookingView(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                status, null, Instant.now(), Instant.now());
    }
}
