package com.seatlock.booking.application;

public class IdempotencyMismatchException extends RuntimeException {
    public IdempotencyMismatchException() {
        super("Idempotency-Key was already used for different booking details");
    }
}
