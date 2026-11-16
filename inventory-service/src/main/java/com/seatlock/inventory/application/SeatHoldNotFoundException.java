package com.seatlock.inventory.application;
public class SeatHoldNotFoundException extends RuntimeException {
    public SeatHoldNotFoundException() { super("Active hold not found for this customer and seat"); }
}
