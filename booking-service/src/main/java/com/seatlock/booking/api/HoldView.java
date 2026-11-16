package com.seatlock.booking.api;
import java.time.Instant;
import java.util.UUID;
public record HoldView(UUID holdId, UUID eventId, UUID seatId,
                       UUID customerId, Instant expiresAt, String status) { }
