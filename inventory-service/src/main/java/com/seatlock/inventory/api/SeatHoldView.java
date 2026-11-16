package com.seatlock.inventory.api;

import java.time.Instant;
import java.util.UUID;

public record SeatHoldView(UUID holdId, UUID eventId, UUID seatId,
                           UUID customerId, Instant expiresAt, String status) { }
