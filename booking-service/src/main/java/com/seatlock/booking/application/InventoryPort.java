package com.seatlock.booking.application;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.domain.BookingStatus;

/** Port (hexagonal architecture): workflow knows nothing about HTTP clients. */
public interface InventoryPort {
    Attempt reserve(BookingView booking);

    record Attempt(BookingStatus status, String reason) {
        public Attempt {
            if (status != BookingStatus.CONFIRMED
                    && status != BookingStatus.REJECTED
                    && status != BookingStatus.UNCERTAIN) {
                throw new IllegalArgumentException("Unexpected reservation attempt status");
            }
        }
    }
}
