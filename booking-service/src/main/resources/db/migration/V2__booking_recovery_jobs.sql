-- Each Booking INSERT and its durable recovery job commit atomically.
-- The job is a PostgreSQL work queue (NOT a Kafka transactional outbox).
CREATE TABLE booking_recovery_jobs (
    booking_id UUID PRIMARY KEY REFERENCES bookings(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL DEFAULT 'READY',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    owner_token UUID,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_recovery_state CHECK (state IN ('READY','LEASED','DONE','EXHAUSTED')),
    CONSTRAINT ck_recovery_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_recovery_lease CHECK (
        (state = 'LEASED' AND owner_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'LEASED' AND owner_token IS NULL AND lease_until IS NULL)
    )
);
CREATE INDEX idx_recovery_due ON booking_recovery_jobs(next_attempt_at, booking_id)
    WHERE state IN ('READY','LEASED');
