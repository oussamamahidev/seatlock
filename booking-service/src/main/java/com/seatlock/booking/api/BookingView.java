package com.seatlock.booking.api;

import com.seatlock.booking.domain.BookingEntity;
import com.seatlock.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;

public record BookingView(UUID id, UUID customerId, UUID eventId, UUID seatId,
                          BookingStatus status, String failureReason,
                          Instant createdAt, Instant updatedAt) {
    public static BookingView from(BookingEntity entity) {
        return new BookingView(entity.getId(), entity.getCustomerId(),
                entity.getEventId(), entity.getSeatId(), entity.getStatus(),
                entity.getFailureReason(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
