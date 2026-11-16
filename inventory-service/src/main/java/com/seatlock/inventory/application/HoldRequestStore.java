package com.seatlock.inventory.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable idempotency history; same hold UUID can never be reassigned after expiry. */
@Repository
public class HoldRequestStore {
    private final JdbcTemplate jdbc;
    public HoldRequestStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Binding> find(UUID id) {
        return jdbc.query("""
                SELECT hold_id, event_id, seat_id, customer_id
                FROM seat_hold_requests WHERE hold_id = ?
                """, (rs, n) -> new Binding(
                        rs.getObject("hold_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("seat_id", UUID.class),
                        rs.getObject("customer_id", UUID.class)), id).stream().findFirst();
    }

    public void insert(UUID holdId, UUID eventId, UUID seatId, UUID owner) {
        int inserted = jdbc.update("""
            INSERT INTO seat_hold_requests(hold_id,event_id,seat_id,customer_id)
            VALUES (?,?,?,?) ON CONFLICT(hold_id) DO NOTHING
            """, holdId, eventId, seatId, owner);
        if (inserted != 1) throw new SeatHoldConflictException("Hold ID was already used");
    }

    public record Binding(UUID holdId, UUID eventId, UUID seatId, UUID customerId) { }
}
