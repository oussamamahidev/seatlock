#!/usr/bin/env bash
# Safe, fail-fast installer. Does NOT run migrations or stop/restart services.
set -euo pipefail
ROOT="$(pwd)"
BUNDLE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH="$BUNDLE/SeatLock_Part075.patch"
for service in booking-service inventory-service gateway-service; do
  if [[ ! -d "$ROOT/$service" ]]; then
    printf 'ERROR: Run from your SeatLock repository root: cd ~/Desktop/seatlock\n' >&2
    exit 2
  fi
done
if [[ ! -f "$PATCH" ]]; then echo "ERROR: Missing $PATCH" >&2; exit 2; fi
printf 'Checking patch compatibility (no files changed yet)...\n'
if ! git apply --check "$PATCH"; then
  cat >&2 <<'NOTE'
Patch does not apply cleanly against your current files (possibly AI CLI recovery changes).
NOTHING WAS CHANGED. Do not use cp -r or --reject to force an overwrite.
Give this patch to your coding CLI and ask it to merge while preserving recovery fixes.
NOTE
  exit 1
fi
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="$HOME/Desktop/seatlock-backups/part075-$STAMP"
mkdir -p "$BACKUP"
for service in booking-service inventory-service gateway-service; do
  cp -a "$ROOT/$service" "$BACKUP/$service"
done
printf 'Backup created at %s\n' "$BACKUP"
git apply "$PATCH"
printf 'Patch applied successfully. NEXT: mvn clean verify on all three services.\n'
