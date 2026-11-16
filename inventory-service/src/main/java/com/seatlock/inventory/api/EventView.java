package com.seatlock.inventory.api;

import com.seatlock.inventory.domain.EventEntity;

import java.time.Instant;
import java.util.UUID;

public record EventView(UUID id, String title, String venue, Instant startsAt) {
    public static EventView from(EventEntity event) {
        return new EventView(event.getId(), event.getTitle(), event.getVenue(), event.getStartsAt());
    }
}
