package com.seatlock.inventory.application;

import java.util.UUID;

public class SeatNotFoundException extends RuntimeException {
    public SeatNotFoundException(UUID seatId, UUID eventId) {
        super("Seat " + seatId + " was not found in event " + eventId);
    }
}
