#!/usr/bin/env bash
# ONLY ADDITIVE seed. Uses a NEW event UUID and 20 NEW seats, never deletes existing data.
set -euo pipefail
cd "$(dirname "$0")/.."
EVENT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d inventory_db' <<SQL
BEGIN;
INSERT INTO events(id,title,venue,starts_at)
VALUES ('$EVENT_ID','SeatLock Concurrency Lab','Test Venue',now()+interval '30 days');
INSERT INTO seats(id,event_id,label,status)
SELECT gen_random_uuid(),'$EVENT_ID','A'||lpad(n::text,2,'0'),'AVAILABLE'
FROM generate_series(1,20) AS s(n);
COMMIT;
SQL
printf '\nCreated isolated test event (20 AVAILABLE seats).\n'
printf 'Run: export SEATLOCK_EVENT_ID=%s\n' "$EVENT_ID"
