#!/usr/bin/env bash
# Launch a Booking *replica*, reusing the exact same built Spring Boot JAR.
# Never edits data/ports, never kills processes. Use local test only.
set -euo pipefail
PORT="${1:-}"
if [[ "$PORT" != "8083" && "$PORT" != "8084" ]]; then
  echo 'Usage: bash scripts/part075_launch_booking_replica.sh 8083|8084' >&2
  exit 2
fi
if [[ -z "${BOOKING_DB_PASSWORD:-}" || -z "${BOOKING_MACHINE_SECRET:-}" ]]; then
  echo 'Missing BOOKING_DB_PASSWORD or BOOKING_MACHINE_SECRET in THIS terminal.' >&2
  echo 'Use read -r -s and export both variables without printing values.' >&2
  exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/booking-service/target/booking-service-0.0.1-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR. First: cd booking-service && mvn clean verify" >&2
  exit 2
fi
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | grep -q LISTEN; then
  echo "Port $PORT already in use. Stop only the old Booking process before launch." >&2
  exit 3
fi
export RECOVERY_ENABLED=true CANCELLATION_ENABLED=true
echo "Booking replica starting on 127.0.0.1:$PORT (uses shared booking_db)."
exec java -jar "$JAR" "--server.port=$PORT" '--server.address=127.0.0.1'
