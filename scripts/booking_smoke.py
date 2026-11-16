#!/usr/bin/env python3
"""SeatLock Part 4 end-to-end smoke test. Consumes three available demo seats."""
import concurrent.futures
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

INVENTORY = os.getenv("INVENTORY_URL", "http://localhost:8082")
BOOKING = os.getenv("BOOKING_URL", "http://localhost:8083")
EVENT = os.getenv("SEATLOCK_EVENT_ID", "11111111-1111-4111-8111-111111111111")
USER = str(uuid.uuid4())


def call(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Content-Type": "application/json", **(headers or {})
    })
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def book(seat_id, key):
    return call("POST", f"{BOOKING}/api/v1/bookings",
                {"eventId": EVENT, "seatId": seat_id},
                {"X-Demo-User": USER, "Idempotency-Key": key})


def assert_status(actual, expected, description):
    if actual != expected:
        raise AssertionError(f"{description}: expected {expected}, got {actual}")
    print(f"PASS {description}: HTTP {actual}")


def run():
    code, data = call("GET", f"{INVENTORY}/api/v1/events/{EVENT}/seats?size=50")
    assert_status(code, 200, "Inventory reachable")
    available = [s["id"] for s in data["content"] if s["status"] == "AVAILABLE"]
    if len(available) < 3:
        sys.exit("Need 3 AVAILABLE seats for this smoke test. Use new demo seats; do NOT delete your volume.")
    seat1, seat2, seat3 = available[:3]

    key1 = "first-" + str(uuid.uuid4())
    code, first = book(seat1, key1)
    assert_status(code, 201, "First booking succeeds")
    assert first["status"] == "CONFIRMED", first

    code, repeated = book(seat1, key1)
    assert_status(code, 200, "Idempotent replay")
    assert first["id"] == repeated["id"]

    code, mismatched = book(seat2, key1)
    assert_status(code, 409, "Same key different payload rejected")

    code, conflict = book(seat1, "conflict-" + str(uuid.uuid4()))
    assert_status(code, 409, "Different booking cannot claim reserved seat")
    assert conflict["status"] == "REJECTED"

    key2 = "concurrent-key-" + str(uuid.uuid4())
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        outputs = list(ex.map(lambda _: book(seat2, key2), range(10)))
    assert all(code in (200, 201, 202) for code, _ in outputs), outputs
    ids = {result["id"] for _, result in outputs}
    assert len(ids) == 1, ("Same key generated multiple bookings", ids)
    print("PASS concurrent same-key requests: exactly one booking id")

    code, final = call("GET", f"{BOOKING}/api/v1/bookings/{next(iter(ids))}",
                       headers={"X-Demo-User": USER})
    assert_status(code, 200, "Get booking")
    assert final["status"] == "CONFIRMED", final

    # Different keys, same new seat: exactly one winner across services.
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        attempts = list(ex.map(lambda _: book(seat3, "racer-" + str(uuid.uuid4())), range(8)))
    winners = [x for x in attempts if x[1].get("status") == "CONFIRMED"]
    losers = [x for x in attempts if x[1].get("status") == "REJECTED"]
    assert len(winners) == 1 and len(losers) == 7, attempts
    assert winners[0][0] == 201 and all(code == 409 for code, _ in losers), attempts
    print("PASS concurrent distinct-key requests: 1 winner / 7 conflicts")
    print("ALL PART 4 SMOKE TESTS PASSED")


if __name__ == "__main__":
    run()
