package com.seatlock.booking.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BookingCancellationStateTest {
    @Test
    void confirmedBookingCanCancelAndDelayedReserveCannotResurrectIt() {
        var booking=new BookingEntity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"test-key-123456","fingerprint");
        booking.transition(BookingStatus.CONFIRMED,null);
        booking.startCancellation();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCEL_PENDING);
        booking.transition(BookingStatus.CONFIRMED,null); // stale remote response ignored
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCEL_PENDING);
        booking.completeCancellation();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        booking.transition(BookingStatus.CONFIRMED,null);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void unconfirmedBookingCannotCancel() {
        var booking=new BookingEntity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"test-key-123456","fingerprint");
        assertThatThrownBy(booking::startCancellation).isInstanceOf(IllegalStateException.class);
    }
}
