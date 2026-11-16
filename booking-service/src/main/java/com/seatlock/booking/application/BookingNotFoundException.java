package com.seatlock.booking.application;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException() {
        super("Booking not found for this customer");
    }
}
