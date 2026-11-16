package com.seatlock.booking.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateBookingRequest(@NotNull UUID eventId, @NotNull UUID seatId) { }
