CREATE TABLE events (
    id UUID PRIMARY KEY,
    title VARCHAR(160) NOT NULL CHECK (length(btrim(title)) > 0),
    venue VARCHAR(160) NOT NULL CHECK (length(btrim(venue)) > 0),
    starts_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE seats (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    label VARCHAR(12) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    CONSTRAINT fk_seats_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT uq_seats_event_label UNIQUE (event_id, label),
    CONSTRAINT ck_seats_status CHECK (status IN ('AVAILABLE', 'RESERVED'))
);
-- The UNIQUE (event_id, label) constraint already creates a useful B-tree index.
