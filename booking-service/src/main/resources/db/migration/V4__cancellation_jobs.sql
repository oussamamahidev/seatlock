-- No destructive data migration. Durable Saga compensation for confirmed bookings.
ALTER TABLE bookings DROP CONSTRAINT ck_bookings_status;
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_status CHECK (
    status IN ('PENDING','CONFIRMED','REJECTED','UNCERTAIN','CANCEL_PENDING','CANCELLED')
);
CREATE TABLE booking_cancellation_jobs (
    booking_id UUID PRIMARY KEY REFERENCES bookings(id) ON DELETE RESTRICT,
    state VARCHAR(16) NOT NULL CHECK(state IN ('READY','LEASED','DONE','EXHAUSTED')),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    owner_token UUID,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(300),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_cancellation_due ON booking_cancellation_jobs(next_attempt_at)
    WHERE state IN ('READY','LEASED');
