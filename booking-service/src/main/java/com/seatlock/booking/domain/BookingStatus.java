package com.seatlock.booking.domain;

public enum BookingStatus {
    PENDING, CONFIRMED, REJECTED, UNCERTAIN;

    public boolean isTerminal() {
        return this == CONFIRMED || this == REJECTED;
    }
}
