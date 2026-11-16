package com.seatlock.inventory.application;
public class SeatHoldConflictException extends RuntimeException {
    public SeatHoldConflictException(String reason) { super(reason); }
}
