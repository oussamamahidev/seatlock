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

    public SeatReservationService(SeatRepository seats) {
        this.seats = seats;
    }

    @Transactional
    public ReservationResult reserve(UUID eventId, UUID seatId, UUID bookingId) {
        // Retry of an already committed booking: no new write required.
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
            changed = seats.claimIfAvailable(eventId, seatId, bookingId);
        } catch (DataIntegrityViolationException ex) {
            if (isReservationIdUniqueViolation(ex)) {
                // A concurrent request used the same bookingId for another seat.
                throw new ReservationConflictException("This bookingId already belongs to another seat", ex);
            }
            throw ex;
        }

        if (changed == 1) {
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
