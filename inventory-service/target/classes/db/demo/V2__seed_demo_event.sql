-- Development-only migration, loaded exclusively with the "local" profile.
INSERT INTO events (id, title, venue, starts_at)
VALUES (
    '11111111-1111-4111-8111-111111111111',
    'SeatLock Live Demo',
    'Rabat Tech Hall',
    '2027-01-20 19:00:00+00'
);

INSERT INTO seats (id, event_id, label, status)
SELECT gen_random_uuid(),
       '11111111-1111-4111-8111-111111111111'::uuid,
       'A' || lpad(n::text, 2, '0'),
       'AVAILABLE'
FROM generate_series(1, 20) AS n;
