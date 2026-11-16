package com.seatlock.booking.application;
import java.util.UUID;
public record CancellationLease(UUID bookingId, UUID token, int attempt) { }
