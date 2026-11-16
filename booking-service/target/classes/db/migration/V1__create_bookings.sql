CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    event_id UUID NOT NULL,
    seat_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_bookings_customer_key UNIQUE (customer_id, idempotency_key),
    CONSTRAINT ck_bookings_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'UNCERTAIN')
    )
);

CREATE INDEX idx_bookings_customer_created
    ON bookings(customer_id, created_at DESC, id);
