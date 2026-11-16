package com.seatlock.inventory.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable booking/seat binding with a cancellation tombstone. */
@Repository
public class ReservationHistoryStore {
    private final JdbcTemplate jdbc;
    public ReservationHistoryStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Optional<Binding> find(UUID bookingId) {
        return jdbc.query("""
            SELECT booking_id,event_id,seat_id,state FROM seat_reservation_history
            WHERE booking_id=?
            """, (rs,n) -> new Binding(
                rs.getObject("booking_id",UUID.class),rs.getObject("event_id",UUID.class),
                rs.getObject("seat_id",UUID.class),rs.getString("state")), bookingId)
                .stream().findFirst();
    }

    /** Lock the booking history row before releasing its seat. */
    public Optional<Binding> lock(UUID bookingId) {
        return jdbc.query("""
            SELECT booking_id,event_id,seat_id,state FROM seat_reservation_history
            WHERE booking_id=? FOR UPDATE
            """, (rs,n) -> new Binding(
                rs.getObject("booking_id",UUID.class),rs.getObject("event_id",UUID.class),
                rs.getObject("seat_id",UUID.class),rs.getString("state")), bookingId)
                .stream().findFirst();
    }

    public void insert(UUID bookingId, UUID eventId, UUID seatId) {
        int inserted=jdbc.update("""
            INSERT INTO seat_reservation_history(booking_id,event_id,seat_id,state)
            VALUES (?,?,?,'ACTIVE') ON CONFLICT(booking_id) DO NOTHING
            """,bookingId,eventId,seatId);
        if(inserted != 1) throw new ReservationConflictException(
                "Booking ID was already used, or its reservation was canceled");
    }

    public void markCanceled(UUID bookingId) {
        int changed=jdbc.update("""
            UPDATE seat_reservation_history SET state='CANCELLED',updated_at=now()
            WHERE booking_id=? AND state='ACTIVE'
            """, bookingId);
        if(changed!=1) throw new IllegalStateException("Reservation history cancellation failed");
    }

    public record Binding(UUID bookingId,UUID eventId,UUID seatId,String state) { }
}
