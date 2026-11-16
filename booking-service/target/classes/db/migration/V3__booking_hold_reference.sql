-- Non-destructive: old bookings retain NULL, new bookings can reference an ephemeral hold.
ALTER TABLE bookings ADD COLUMN hold_id UUID;
CREATE INDEX idx_bookings_hold_id ON bookings(hold_id) WHERE hold_id IS NOT NULL;
