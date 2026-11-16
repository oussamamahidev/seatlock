package com.seatlock.booking.application;
import com.seatlock.booking.api.HoldView;
import java.util.UUID;

/** Hexagonal application port; machine JWT stays in HTTP adapter, never reaches controller. */
public interface HoldPort {
    HoldAttempt create(UUID eventId, UUID seatId, UUID customerId, UUID holdId);
    void cancel(UUID eventId, UUID seatId, UUID customerId, UUID holdId);
    record HoldAttempt(HoldView receipt, boolean created) { }
}
