-- Add a separate, expiring HELD state. NEVER expire a committed RESERVED seat.
ALTER TABLE seats
    ADD COLUMN hold_id UUID,
    ADD COLUMN hold_customer_id UUID,
    ADD COLUMN hold_expires_at TIMESTAMPTZ;

-- V1 also had a status-only CHECK; both constraints must accept HELD.
ALTER TABLE seats DROP CONSTRAINT ck_seats_status;
ALTER TABLE seats ADD CONSTRAINT ck_seats_status
    CHECK (status IN ('AVAILABLE','HELD','RESERVED'));
ALTER TABLE seats DROP CONSTRAINT ck_seats_reservation_state;
ALTER TABLE seats ADD CONSTRAINT ck_seats_lifecycle_state CHECK (
    (status = 'AVAILABLE' AND reservation_id IS NULL AND hold_id IS NULL
        AND hold_customer_id IS NULL AND hold_expires_at IS NULL)
 OR (status = 'HELD' AND reservation_id IS NULL AND hold_id IS NOT NULL
        AND hold_customer_id IS NOT NULL AND hold_expires_at IS NOT NULL)
 OR (status = 'RESERVED' AND reservation_id IS NOT NULL AND hold_id IS NULL
        AND hold_customer_id IS NULL AND hold_expires_at IS NULL)
);

CREATE UNIQUE INDEX uq_seats_hold_id ON seats(hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX idx_seats_held_expires_at ON seats(hold_expires_at) WHERE status = 'HELD';

-- Immutable idempotency tombstone: canceled/expired hold IDs cannot be rebound.
CREATE TABLE seat_hold_requests (
    hold_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    seat_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
