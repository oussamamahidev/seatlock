package com.seatlock.booking.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed durable work queue. No network calls while row locks are held. */
@Repository
public class RecoveryJobStore {
    private final JdbcTemplate jdbc;

    public RecoveryJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called in the same transaction as Booking INSERT. */
    public void create(UUID bookingId) {
        jdbc.update("""
            INSERT INTO booking_recovery_jobs
                (booking_id, state, next_attempt_at)
            VALUES (?, 'READY', now() + interval '5 seconds')
            """, bookingId);
    }

    /** A terminal decision makes pending retries unnecessary. */
    public void markDone(UUID bookingId) {
        jdbc.update("""
            UPDATE booking_recovery_jobs
            SET state = 'DONE', owner_token = NULL, lease_until = NULL,
                last_error = NULL, updated_at = now()
            WHERE booking_id = ? AND state <> 'DONE'
            """, bookingId);
    }

    /** For a synchronous timeout, activate the job again without stealing a live lease. */
    public void activateAfterUnknown(UUID bookingId) {
        jdbc.update("""
            UPDATE booking_recovery_jobs
            SET next_attempt_at = now() + interval '5 seconds',
                last_error = 'Remote outcome unknown', updated_at = now()
            WHERE booking_id = ? AND state = 'READY'
            """, bookingId);
    }

    /** A single statement acquires at most N disjoint jobs even across multiple JVMs. */
    @Transactional
    public List<RecoveryLease> claim(int maxBatch, int leaseSeconds) {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
            WITH due AS (
                SELECT j.booking_id
                FROM booking_recovery_jobs j
                JOIN bookings b ON b.id = j.booking_id
                WHERE b.status IN ('PENDING', 'UNCERTAIN')
                  AND ((j.state = 'READY' AND j.next_attempt_at <= now())
                    OR (j.state = 'LEASED' AND j.lease_until < now()))
                ORDER BY j.next_attempt_at, j.booking_id
                LIMIT ? FOR UPDATE OF j SKIP LOCKED
            )
            UPDATE booking_recovery_jobs j
            SET state = 'LEASED', owner_token = ?,
                lease_until = now() + (? * interval '1 second'),
                attempt_count = j.attempt_count + 1, updated_at = now()
            FROM due WHERE j.booking_id = due.booking_id
            RETURNING j.booking_id, j.attempt_count
            """, (rs, rowNum) -> new RecoveryLease(
                rs.getObject("booking_id", UUID.class), token, rs.getInt("attempt_count")),
            maxBatch, token, leaseSeconds);
    }

    /** Use wall time: PostgreSQL now() stays frozen while a transaction waits for locks. */
    public boolean ownsLease(RecoveryLease lease) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM booking_recovery_jobs
            WHERE booking_id = ? AND state = 'LEASED'
              AND owner_token = ? AND lease_until > clock_timestamp()
            """, Integer.class, lease.bookingId(), lease.token());
        return count != null && count == 1;
    }

    public void finish(RecoveryLease lease) {
        int rows = jdbc.update("""
            UPDATE booking_recovery_jobs
            SET state = 'DONE', owner_token = NULL, lease_until = NULL,
                last_error = NULL, updated_at = now()
            WHERE booking_id = ? AND state = 'LEASED' AND owner_token = ?
              AND lease_until > clock_timestamp()
            """, lease.bookingId(), lease.token());
        if (rows != 1) throw new IllegalStateException("Recovery lease was lost");
    }

    public void postpone(RecoveryLease lease, Instant next, String reason, boolean exhausted) {
        String state = exhausted ? "EXHAUSTED" : "READY";
        int rows = jdbc.update("""
            UPDATE booking_recovery_jobs
            SET state = ?, next_attempt_at = ?, owner_token = NULL,
                lease_until = NULL, last_error = ?, updated_at = now()
            WHERE booking_id = ? AND state = 'LEASED' AND owner_token = ?
              AND lease_until > clock_timestamp()
            """, state, Timestamp.from(next), reason, lease.bookingId(), lease.token());
        if (rows != 1) throw new IllegalStateException("Recovery lease was lost");
    }
}
