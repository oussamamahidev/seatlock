package com.seatlock.booking.application;
public class HoldConflictException extends RuntimeException {
    public HoldConflictException(String message) { super(message); }
}
