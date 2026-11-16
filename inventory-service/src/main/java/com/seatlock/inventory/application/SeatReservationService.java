package com.seatlock.inventory.application;

import com.seatlock.inventory.domain.SeatEntity;
import com.seatlock.inventory.domain.SeatStatus;
import com.seatlock.inventory.persistence.SeatRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

@Service
public class SeatReservationService {
    private final SeatRepository seats;
    private final ReservationHistoryStore history;

    public SeatReservationService(SeatRepository seats, ReservationHistoryStore history) {
        this.seats = seats;
        this.history = history;
    }

    @Transactional
    public ReservationResult reserve(UUID eventId, UUID seatId, UUID bookingId) {
        return reserve(eventId, seatId, bookingId, null, null);
    }

    @Transactional
    public ReservationResult reserve(UUID eventId, UUID seatId, UUID bookingId,
                                     UUID holdId, UUID customerId) {
        if ((holdId == null) != (customerId == null)) {
            throw new IllegalArgumentException("holdId and customerId must both be present");
        }
        // A cancellation tombstone blocks any late retry from resurrecting a seat.
        var recorded = history.lock(bookingId);
        if (recorded.isPresent()) {
            var binding = recorded.get();
            if (!binding.eventId().equals(eventId) || !binding.seatId().equals(seatId)
                    || !"ACTIVE".equals(binding.state())) {
                throw new ReservationConflictException("Booking ID was canceled or bound to another seat");
            }
            var active = seats.findByReservationId(bookingId);
            if (active.isEmpty() || !active.get().getId().equals(seatId)) {
                throw new IllegalStateException("ACTIVE reservation history does not match seat ownership");
            }
            return new ReservationResult(eventId, seatId, bookingId, false);
        }

        // Legacy idempotent reservation path. All successful new claims also enter history.
        var existing = seats.findByReservationId(bookingId);
        if (existing.isPresent()) {
            var seat = existing.get();
            if (seat.getId().equals(seatId)
                    && seat.getEvent().getId().equals(eventId)
                    && seat.getStatus() == SeatStatus.RESERVED) {
                return new ReservationResult(eventId, seatId, bookingId, false);
            }
            throw new ReservationConflictException("This bookingId already belongs to another seat");
        }

        // One conditional UPDATE, not SELECT followed by save().
        final int changed;
        try {
            changed = holdId == null
                    ? seats.claimIfAvailable(eventId, seatId, bookingId)
                    : seats.claimOwnedHold(eventId, seatId, bookingId, holdId, customerId);
        } catch (DataIntegrityViolationException ex) {
            if (isReservationIdUniqueViolation(ex)) {
                // A concurrent request used the same bookingId for another seat.
                throw new ReservationConflictException("This bookingId already belongs to another seat", ex);
            }
            throw ex;
        }

        if (changed == 1) {
            // Same DB transaction as conditional seat UPDATE; no post-cancel resurrection.
            history.insert(bookingId, eventId, seatId);
            return new ReservationResult(eventId, seatId, bookingId, true);
        }

        // Another transaction won, or the seat does not belong to this event.
        SeatEntity seat = seats.findById(seatId)
                .filter(s -> s.getEvent().getId().equals(eventId))
                .orElseThrow(() -> new SeatNotFoundException(seatId, eventId));

        // An in-flight duplicate request may have completed while we waited.
        if (seat.getStatus() == SeatStatus.RESERVED
                && bookingId.equals(seat.getReservationId())) {
            return new ReservationResult(eventId, seatId, bookingId, false);
        }
        throw new ReservationConflictException("Seat is already reserved");
    }

    /** Idempotent CAS compensation: never releases a new booking owner's seat. */
    @Transactional
    public void release(UUID eventId, UUID seatId, UUID bookingId) {
        var binding = history.lock(bookingId)
                .orElseThrow(() -> new IllegalStateException("Missing reservation history"));
        if (!binding.eventId().equals(eventId) || !binding.seatId().equals(seatId)) {
            throw new ReservationConflictException("Booking ID belongs to another seat");
        }
        if ("CANCELLED".equals(binding.state())) return; // idempotent replay
        int updated = seats.releaseReservationIfOwned(eventId, seatId, bookingId);
        if (updated != 1) throw new IllegalStateException("Reservation history/seat ownership mismatch");
        history.markCanceled(bookingId);
    }

    private static boolean isReservationIdUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && String.valueOf(sql.getMessage()).contains("uq_seats_reservation_id")) {
                return true;
            }
        }
        return false;
    }
}
