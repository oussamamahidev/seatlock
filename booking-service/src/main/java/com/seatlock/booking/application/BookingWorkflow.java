package com.seatlock.booking.application;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.api.CreateBookingRequest;
import com.seatlock.booking.domain.BookingStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.regex.Pattern;

/** Orchestrates independent local transactions and an idempotent remote call. */
@Service
public class BookingWorkflow {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{8,128}");
    private final BookingStore store;
    private final InventoryPort inventory;

    public BookingWorkflow(BookingStore store, InventoryPort inventory) {
        this.store = store;
        this.inventory = inventory;
    }

    public Outcome create(UUID customerId, String key, CreateBookingRequest request) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8-128 letters, digits, dots, underscores or hyphens");
        }
        String fingerprint = fingerprint(request);
        var original = store.byCustomerAndKey(customerId, key);
        if (original.isPresent()) {
            requireSameRequest(original.get(), fingerprint);
            return new Outcome(original.get(), false);
        }

        final BookingView pending;
        try {
            pending = store.insert(customerId, key,
                    request.eventId(), request.seatId(), fingerprint);
        } catch (DataIntegrityViolationException duplicate) {
            // The other concurrent INSERT committed its unique key first.
            BookingView winner = store.byCustomerAndKey(customerId, key)
                    .orElseThrow(() -> duplicate);
            requireSameRequest(winner, fingerprint);
            return new Outcome(winner, false);
        }
        return new Outcome(attempt(pending), true);
    }

    public BookingView get(UUID customerId, UUID id) {
        return store.ownedBooking(customerId, id);
    }

    public BookingView retry(UUID customerId, UUID id) {
        BookingView current = store.ownedBooking(customerId, id);
        if (current.status().isTerminal()) {
            return current;
        }
        // Same bookingId => Inventory's existing idempotency guard applies.
        return attempt(current);
    }

    private BookingView attempt(BookingView booking) {
        InventoryPort.Attempt outcome = inventory.reserve(booking);
        return store.transition(booking.id(), outcome.status(), outcome.reason());
    }

    private static String fingerprint(CreateBookingRequest request) {
        // Stable and unambiguous for the two UUID fields in this MVP.
        return request.eventId() + ":" + request.seatId();
    }

    private static void requireSameRequest(BookingView existing, String fingerprint) {
        String recorded = existing.eventId() + ":" + existing.seatId();
        if (!recorded.equals(fingerprint)) {
            throw new IdempotencyMismatchException();
        }
    }

    public record Outcome(BookingView booking, boolean created) { }
}
