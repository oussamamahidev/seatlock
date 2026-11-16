package com.seatlock.booking.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable cancellations and row-locked leases across arbitrary Booking replicas. */
@Repository
public class CancellationJobStore {
    private final JdbcTemplate jdbc;
    public CancellationJobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(UUID bookingId) {
        jdbc.update("""
            INSERT INTO booking_cancellation_jobs(booking_id,state,next_attempt_at)
            VALUES (?,'READY',now()) ON CONFLICT (booking_id) DO NOTHING
            """, bookingId);
    }

    @Transactional
    public List<CancellationLease> claim(int maxBatch, int leaseSeconds) {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
            WITH due AS (
                SELECT j.booking_id FROM booking_cancellation_jobs j
                JOIN bookings b ON b.id=j.booking_id
                WHERE b.status='CANCEL_PENDING'
                  AND ((j.state='READY' AND j.next_attempt_at <= now())
                    OR (j.state='LEASED' AND j.lease_until < now()))
                ORDER BY j.next_attempt_at, j.booking_id
                LIMIT ? FOR UPDATE OF j SKIP LOCKED
            )
            UPDATE booking_cancellation_jobs j SET state='LEASED',owner_token=?,
                lease_until=now() + (? * interval '1 second'),
                attempt_count=j.attempt_count+1,updated_at=now()
            FROM due WHERE j.booking_id=due.booking_id
            RETURNING j.booking_id,j.attempt_count
            """, (rs, num) -> new CancellationLease(
                    rs.getObject("booking_id", UUID.class), token, rs.getInt("attempt_count")),
                    maxBatch, token, leaseSeconds);
    }

    public boolean ownsLease(CancellationLease lease) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM booking_cancellation_jobs
            WHERE booking_id=? AND state='LEASED' AND owner_token=? AND lease_until>now()
            """, Integer.class, lease.bookingId(),lease.token());
        return count!=null && count==1;
    }

    public void finish(CancellationLease lease) {
        int changed=jdbc.update("""
            UPDATE booking_cancellation_jobs SET state='DONE',owner_token=NULL,
                lease_until=NULL,last_error=NULL,updated_at=now()
            WHERE booking_id=? AND state='LEASED' AND owner_token=? AND lease_until>now()
            """, lease.bookingId(),lease.token());
        if(changed!=1) throw new IllegalStateException("Cancellation lease lost");
    }

    public void postpone(CancellationLease lease, Instant next, boolean exhausted) {
        int changed=jdbc.update("""
            UPDATE booking_cancellation_jobs SET state=?,owner_token=NULL,
                lease_until=NULL,last_error='Inventory release outcome unknown',
                next_attempt_at=?,updated_at=now()
            WHERE booking_id=? AND state='LEASED' AND owner_token=? AND lease_until>now()
            """, exhausted ? "EXHAUSTED" : "READY", Timestamp.from(next),
                lease.bookingId(),lease.token());
        if(changed!=1) throw new IllegalStateException("Cancellation lease lost");
    }
}
