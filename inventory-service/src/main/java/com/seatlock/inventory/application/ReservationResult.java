package com.seatlock.inventory.application;

import java.util.UUID;

public record ReservationResult(
        UUID eventId, UUID seatId, UUID bookingId, boolean created
) { }
