package com.seatlock.inventory.application;

import com.seatlock.inventory.domain.SeatEntity;
import com.seatlock.inventory.persistence.SeatRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SeatReservationServiceTest {
    private final SeatRepository seats = mock(SeatRepository.class);
    private final ReservationHistoryStore history = mock(ReservationHistoryStore.class);
    private final SeatReservationService service = new SeatReservationService(seats, history);

    @Test
    void firstReservationClaimsSeat() {
        UUID event = UUID.randomUUID(), seat = UUID.randomUUID(), booking = UUID.randomUUID();
        when(seats.findByReservationId(booking)).thenReturn(Optional.empty());
        when(seats.claimIfAvailable(event, seat, booking)).thenReturn(1);

        var response = service.reserve(event, seat, booking);

        assertThat(response.created()).isTrue();
        assertThat(response.bookingId()).isEqualTo(booking);
        verify(seats).claimIfAvailable(event, seat, booking);
    }

    @Test
    void failedClaimOfMissingSeatReturnsNotFound() {
        UUID event = UUID.randomUUID(), seat = UUID.randomUUID(), booking = UUID.randomUUID();
        when(seats.findByReservationId(booking)).thenReturn(Optional.empty());
        when(seats.claimIfAvailable(event, seat, booking)).thenReturn(0);
        when(seats.findById(seat)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(event, seat, booking))
                .isInstanceOf(SeatNotFoundException.class);
    }

    @Test
    void cannotReuseSameBookingIdForAnotherSeat() {
        UUID event = UUID.randomUUID(), seat = UUID.randomUUID(), booking = UUID.randomUUID();
        SeatEntity other = mock(SeatEntity.class);
        when(other.getId()).thenReturn(UUID.randomUUID());
        when(seats.findByReservationId(booking)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.reserve(event, seat, booking))
                .isInstanceOf(ReservationConflictException.class);
        verify(seats, never()).claimIfAvailable(any(), any(), any());
    }

    @Test
    void occupiedSeatCannotBeClaimed() {
        UUID event = UUID.randomUUID(), seat = UUID.randomUUID(), booking = UUID.randomUUID();
        SeatEntity existing = mock(SeatEntity.class);
        com.seatlock.inventory.domain.EventEntity existingEvent = mock(com.seatlock.inventory.domain.EventEntity.class);
        when(existingEvent.getId()).thenReturn(event);
        when(existing.getEvent()).thenReturn(existingEvent);
        when(existing.getStatus()).thenReturn(com.seatlock.inventory.domain.SeatStatus.RESERVED);
        when(seats.findByReservationId(booking)).thenReturn(Optional.empty());
        when(seats.claimIfAvailable(event, seat, booking)).thenReturn(0);
        when(seats.findById(seat)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reserve(event, seat, booking))
                .isInstanceOf(ReservationConflictException.class);
    }
}
