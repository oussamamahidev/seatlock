package com.seatlock.booking.api;

import com.seatlock.booking.domain.BookingEntity;
import com.seatlock.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;

public record BookingView(UUID id, UUID customerId, UUID eventId, UUID seatId,
                          BookingStatus status, String failureReason,
                          Instant createdAt, Instant updatedAt, UUID holdId) {
    // Preserve the constructor used by earlier Part 4/7 unit tests.
    public BookingView(UUID id, UUID customerId, UUID eventId, UUID seatId,
                       BookingStatus status, String failureReason,
                       Instant createdAt, Instant updatedAt) {
        this(id, customerId, eventId, seatId, status, failureReason, createdAt, updatedAt, null);
    }
    public static BookingView from(BookingEntity entity) {
        return new BookingView(entity.getId(), entity.getCustomerId(),
                entity.getEventId(), entity.getSeatId(), entity.getStatus(),
                entity.getFailureReason(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getHoldId());
    }
}
