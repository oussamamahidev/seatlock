package com.seatlock.inventory.application;

import com.seatlock.inventory.api.SeatHoldView;
import com.seatlock.inventory.domain.SeatEntity;
import com.seatlock.inventory.domain.SeatStatus;
import com.seatlock.inventory.persistence.SeatRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short-lived claim; NOT a confirmed reservation. PostgreSQL row locks serialize competing updates. */
@Service
public class SeatHoldService {
    private final SeatRepository seats;
    private final HoldRequestStore history;
    private final int ttlSeconds;

    public SeatHoldService(SeatRepository seats, HoldRequestStore history,
            @Value("${seatlock.holds.ttl-seconds:120}") int ttlSeconds) {
        if (ttlSeconds < 10 || ttlSeconds > 600) {
            throw new IllegalArgumentException("Hold TTL must be between 10 and 600 seconds");
        }
        this.seats = seats;
        this.history = history;
        this.ttlSeconds = ttlSeconds;
    }

    @Transactional
    public Outcome create(UUID eventId, UUID seatId, UUID holdId, UUID customerId) {
        var prior = history.find(holdId);
        if (prior.isPresent()) {
            var binding = prior.get();
            if (!binding.eventId().equals(eventId) || !binding.seatId().equals(seatId)
                    || !binding.customerId().equals(customerId)) {
                throw new SeatHoldConflictException("Hold ID already bound to a different request");
            }
            var active = seats.findByHoldId(holdId);
            if (active.isEmpty()) throw new SeatHoldConflictException("Hold was consumed or expired; use a new key");
            return new Outcome(validateReplay(active.get(), eventId, seatId, customerId), false);
        }
        var existing = seats.findByHoldId(holdId);
        if (existing.isPresent()) return new Outcome(validateReplay(existing.get(), eventId, seatId, customerId), false);
        int updated;
        try {
            updated = seats.holdIfAvailable(eventId, seatId, holdId, customerId, ttlSeconds);
        } catch (DataIntegrityViolationException ex) {
            // A hold ID cannot be assigned to two seats. Never make an alternate claim.
            throw new SeatHoldConflictException("Hold ID already in use");
        }
        if (updated == 0) {
            var seat = seats.findById(seatId)
                .filter(s -> s.getEvent().getId().equals(eventId))
                .orElseThrow(() -> new SeatNotFoundException(seatId, eventId));
            if (holdId.equals(seat.getHoldId())) {
                return new Outcome(validateReplay(seat, eventId, seatId, customerId), false);
            }
            throw new SeatHoldConflictException("Seat unavailable for a temporary hold");
        }
        // Same ACID transaction as seat UPDATE. On concurrent duplicate INSERT,
        // PostgreSQL rejects one without ever committing a second seat hold.
        history.insert(holdId, eventId, seatId, customerId);
        SeatEntity held = seats.findByHoldId(holdId)
                .orElseThrow(() -> new IllegalStateException("Hold claim was not visible"));
        return new Outcome(view(held), true);
    }

    @Transactional
    public void cancel(UUID eventId, UUID seatId, UUID holdId, UUID customerId) {
        int updated = seats.releaseOwnedHold(eventId, seatId, holdId, customerId);
        if (updated != 1) throw new SeatHoldNotFoundException();
    }

    @Transactional
    public int expireDue() {
        // Multiple workers can run this safely: UPDATE only matching HELD rows.
        return seats.expireDueHolds();
    }

    private static SeatHoldView validateReplay(SeatEntity seat, UUID event, UUID seatId, UUID owner) {
        if (!seat.getId().equals(seatId) || !seat.getEvent().getId().equals(event)
                || !owner.equals(seat.getHoldCustomerId()) || seat.getStatus() != SeatStatus.HELD
                || !seat.getHoldExpiresAt().isAfter(Instant.now())) {
            throw new SeatHoldConflictException("Hold ID is already bound, expired, or not owned");
        }
        return view(seat);
    }

    private static SeatHoldView view(SeatEntity seat) {
        return new SeatHoldView(seat.getHoldId(), seat.getEvent().getId(), seat.getId(),
                seat.getHoldCustomerId(), seat.getHoldExpiresAt(), "HELD");
    }

    public record Outcome(SeatHoldView receipt, boolean created) { }
}
