package com.seatlock.inventory.api;

import java.util.UUID;

import com.seatlock.inventory.domain.SeatEntity;
import com.seatlock.inventory.domain.SeatStatus;

public record SeatView(UUID id, String label, SeatStatus status) {
    public static SeatView from(SeatEntity seat) {
        return new SeatView(seat.getId(), seat.getLabel(), seat.getStatus());
    }
}
