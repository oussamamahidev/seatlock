-- Prevent canceled reservations from being resurrected by late retry deliveries.
CREATE TABLE seat_reservation_history (
    booking_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    seat_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL CHECK(state IN ('ACTIVE', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backfill all previously confirmed seats. No historical data is erased.
INSERT INTO seat_reservation_history (booking_id, event_id, seat_id, state)
SELECT reservation_id, event_id, id, 'ACTIVE'
FROM seats
WHERE status = 'RESERVED' AND reservation_id IS NOT NULL;
CREATE INDEX idx_seat_reservation_history_state ON seat_reservation_history(state,updated_at);
