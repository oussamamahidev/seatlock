package com.seatlock.booking.domain;

public enum BookingStatus {
    PENDING, CONFIRMED, REJECTED, UNCERTAIN, CANCEL_PENDING, CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == REJECTED || this == CANCELLED;
    }
}
