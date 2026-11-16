package com.seatlock.inventory.api;

import java.util.UUID;

/** Public response: never exposes database entities directly. */
public record ReservationView(
        UUID eventId,
        UUID seatId,
        UUID bookingId,
        String status
) { }
