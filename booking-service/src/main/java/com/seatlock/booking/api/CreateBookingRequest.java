package com.seatlock.booking.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateBookingRequest(@NotNull UUID eventId, @NotNull UUID seatId,
                                   UUID holdId) {
    public CreateBookingRequest(UUID eventId, UUID seatId) { this(eventId, seatId, null); }
}
