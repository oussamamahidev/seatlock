-- Never rewrite an applied Flyway migration (V1/V2).
ALTER TABLE seats
    ADD COLUMN reservation_id UUID;

-- A booking UUID may own at most one seat. NULL is allowed for available seats.
CREATE UNIQUE INDEX uq_seats_reservation_id
    ON seats (reservation_id)
    WHERE reservation_id IS NOT NULL;

-- Enforce the status/owner invariant even when clients bypass Hibernate.
ALTER TABLE seats
    ADD CONSTRAINT ck_seats_reservation_state
    CHECK (
        (status = 'AVAILABLE' AND reservation_id IS NULL)
        OR (status = 'RESERVED' AND reservation_id IS NOT NULL)
    );
