package com.seatlock.booking.application;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.api.CreateBookingRequest;
import com.seatlock.booking.domain.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingWorkflowTest {
    @Mock BookingStore store;
    @Mock InventoryPort inventory;
    BookingWorkflow workflow;

    UUID customer = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    UUID seat = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    String key = "my-test-key-123";
    CreateBookingRequest request;
    BookingView pending;
    BookingView confirmed;

    @BeforeEach
    void setup() {
        workflow = new BookingWorkflow(store, inventory);
        request = new CreateBookingRequest(event, seat);
        pending = view(BookingStatus.PENDING);
        confirmed = view(BookingStatus.CONFIRMED);
    }

    BookingView view(BookingStatus status) {
        return new BookingView(id, customer, event, seat, status, null, Instant.now(), Instant.now());
    }

    @Test
    void firstRequestSavesPendingThenConfirmsInventory() {
        when(store.byCustomerAndKey(customer, key)).thenReturn(Optional.empty());
        when(store.insert(customer, key, event, seat, event + ":" + seat)).thenReturn(pending);
        when(inventory.reserve(pending)).thenReturn(new InventoryPort.Attempt(BookingStatus.CONFIRMED, null));
        when(store.transition(id, BookingStatus.CONFIRMED, null)).thenReturn(confirmed);

        var outcome = workflow.create(customer, key, request);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        var order = inOrder(store, inventory);
        order.verify(store).insert(customer, key, event, seat, event + ":" + seat);
        order.verify(inventory).reserve(pending);
        order.verify(store).transition(id, BookingStatus.CONFIRMED, null);
    }

    @Test
    void existingKeyReturnsExistingResultWithoutCallingInventory() {
        when(store.byCustomerAndKey(customer, key)).thenReturn(Optional.of(confirmed));
        var outcome = workflow.create(customer, key, request);
        assertThat(outcome.created()).isFalse();
        assertThat(outcome.booking().id()).isEqualTo(id);
        verifyNoInteractions(inventory);
    }

    @Test
    void sameKeyWithDifferentSeatIsRejected() {
        when(store.byCustomerAndKey(customer, key)).thenReturn(Optional.of(confirmed));
        assertThatThrownBy(() -> workflow.create(customer, key,
                new CreateBookingRequest(event, UUID.randomUUID())))
                .isInstanceOf(IdempotencyMismatchException.class);
        verifyNoInteractions(inventory);
    }

    @Test
    void unknownInventoryOutcomeRemainsUncertainAndCanRetry() {
        BookingView uncertain = view(BookingStatus.UNCERTAIN);
        when(store.ownedBooking(customer, id)).thenReturn(uncertain);
        when(inventory.reserve(uncertain)).thenReturn(new InventoryPort.Attempt(BookingStatus.CONFIRMED, null));
        when(store.transition(id, BookingStatus.CONFIRMED, null)).thenReturn(confirmed);

        BookingView result = workflow.retry(customer, id);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(inventory).reserve(uncertain);
    }

    @Test
    void confirmedBookingIsNotRepeatedOnRetry() {
        when(store.ownedBooking(customer, id)).thenReturn(confirmed);
        assertThat(workflow.retry(customer, id).status()).isEqualTo(BookingStatus.CONFIRMED);
        verifyNoInteractions(inventory);
    }

    @Test
    void shortIdempotencyKeyIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> workflow.create(customer, "bad", request))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store, inventory);
    }
}
