package com.seatlock.booking.application;

import java.util.UUID;

/** Lease fencing token changes at each acquisition. */
public record RecoveryLease(UUID bookingId, UUID token, int attempt) { }
