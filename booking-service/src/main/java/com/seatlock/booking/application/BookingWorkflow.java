package com.seatlock.booking.application;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.api.CreateBookingRequest;
import com.seatlock.booking.domain.BookingStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
            pending = request.holdId() == null
                    ? store.insert(customerId, key, request.eventId(), request.seatId(), fingerprint)
                    : store.insert(customerId, key, request.eventId(), request.seatId(), fingerprint,
                            request.holdId());
        } catch (DataIntegrityViolationException duplicate) {
            // The other concurrent INSERT committed its unique key first.
            BookingView winner = store.byCustomerAndKey(customerId, key)
                    .orElseThrow(() -> duplicate);
            requireSameRequest(winner, fingerprint);
            return new Outcome(winner, false);
        }
        return new Outcome(attempt(pending), true);
    }

    public BookingView cancel(UUID customerId, UUID id) {
        return store.beginCancellation(customerId, id);
    }

    public BookingView get(UUID customerId, UUID id) {
        return store.ownedBooking(customerId, id);
    }

    public BookingView retry(UUID customerId, UUID id) {
        BookingView current = store.ownedBooking(customerId, id);
        if (current.status().isTerminal()
                || current.status() == BookingStatus.CANCEL_PENDING) {
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
        return fingerprint(request.eventId(), request.seatId(), request.holdId());
    }

    private static String fingerprint(UUID eventId, UUID seatId, UUID holdId) {
        if (holdId == null) return eventId + ":" + seatId; // Backward compatible fingerprint
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    ("hold-v1:" + eventId + ":" + seatId + ":" + holdId)
                        .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void requireSameRequest(BookingView existing, String fingerprint) {
        String recorded = fingerprint(existing.eventId(), existing.seatId(), existing.holdId());
        if (!recorded.equals(fingerprint)) throw new IdempotencyMismatchException();
    }

    public record Outcome(BookingView booking, boolean created) { }
}
