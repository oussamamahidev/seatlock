package com.seatlock.booking.application;

import com.seatlock.booking.api.CreateHoldRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic hold UUID makes retries safe even if remote response was lost. */
@Service
public class HoldWorkflow {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{8,128}");
    private final HoldPort inventory;
    public HoldWorkflow(HoldPort inventory) { this.inventory = inventory; }

    public HoldPort.HoldAttempt create(UUID owner, String idempotencyKey, CreateHoldRequest request) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must contain 8-128 safe characters");
        }
        UUID holdId = holdId(owner, idempotencyKey);
        return inventory.create(request.eventId(), request.seatId(), owner, holdId);
    }

    public void cancel(UUID owner, UUID eventId, UUID seatId, UUID holdId) {
        inventory.cancel(eventId, seatId, owner, holdId);
    }

    static UUID holdId(UUID owner, String key) {
        // Stable per authenticated customer + key across multiple Booking replicas.
        return UUID.nameUUIDFromBytes(("seatlock-hold-v1:" + owner + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }
}
