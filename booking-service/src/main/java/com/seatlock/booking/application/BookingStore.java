package com.seatlock.booking.application;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.domain.BookingEntity;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.booking.persistence.BookingRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local ACID transactions only. Never holds a DB lock during a network request. */
@Service
public class BookingStore {
    private final BookingRepository bookings;
    private final RecoveryJobStore jobs;
    private final RetryBackoff backoff;
    private final CancellationJobStore cancellations;

    public BookingStore(BookingRepository bookings, RecoveryJobStore jobs,
                        RetryBackoff backoff, CancellationJobStore cancellations) {
        this.bookings = bookings;
        this.jobs = jobs;
        this.backoff = backoff;
        this.cancellations = cancellations;
    }

    @Transactional(readOnly = true)
    public Optional<BookingView> byCustomerAndKey(UUID customerId, String key) {
        return bookings.findByCustomerIdAndIdempotencyKey(customerId, key).map(BookingView::from);
    }

    @Transactional(readOnly = true)
    public BookingView ownedBooking(UUID customerId, UUID bookingId) {
        return bookings.findByIdAndCustomerId(bookingId, customerId)
                .map(BookingView::from)
                .orElseThrow(BookingNotFoundException::new);
    }

    /** Internal worker read; never expose this method in the customer API. */
    @Transactional(readOnly = true)
    public BookingView internalBooking(UUID bookingId) {
        return bookings.findById(bookingId).map(BookingView::from)
                .orElseThrow(BookingNotFoundException::new);
    }

    @Transactional
    public BookingView insert(UUID customerId, String key,
                              UUID eventId, UUID seatId, String fingerprint) {
        return insert(customerId, key, eventId, seatId, fingerprint, null);
    }

    @Transactional
    public BookingView insert(UUID customerId, String key,
                              UUID eventId, UUID seatId, String fingerprint, UUID holdId) {
        BookingEntity booking = new BookingEntity(
                UUID.randomUUID(), customerId, eventId, seatId, key, fingerprint, holdId);
        BookingView result = BookingView.from(bookings.saveAndFlush(booking));
        // Same transaction: a crash cannot leave a booking with no recovery job.
        jobs.create(result.id());
        return result;
    }

    @Transactional
    public BookingView transition(UUID bookingId, BookingStatus status, String reason) {
        BookingEntity booking = bookings.lockById(bookingId)
                .orElseThrow(BookingNotFoundException::new);
        booking.transition(status, reason);
        if (booking.getStatus().isTerminal()) jobs.markDone(bookingId);
        else if (status == BookingStatus.UNCERTAIN) jobs.activateAfterUnknown(bookingId);
        return BookingView.from(booking);
    }

    /** ACID: CANCEL_PENDING booking and its cancellation job commit together. */
    @Transactional
    public BookingView beginCancellation(UUID customerId, UUID bookingId) {
        BookingEntity booking = bookings.lockById(bookingId)
                .orElseThrow(BookingNotFoundException::new);
        if (!booking.getCustomerId().equals(customerId)) throw new BookingNotFoundException();
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            booking.startCancellation();
            cancellations.create(bookingId);
        } else if (booking.getStatus() != BookingStatus.CANCEL_PENDING
                && booking.getStatus() != BookingStatus.CANCELLED) {
            throw new BookingCancellationConflictException(
                    "Only CONFIRMED bookings may be canceled");
        }
        return BookingView.from(booking);
    }

    /** Fenced worker settlement: no stale worker may mark a booking canceled. */
    @Transactional
    public BookingView settleCancellation(CancellationLease lease, boolean released, int maxAttempts) {
        BookingEntity booking = bookings.lockById(lease.bookingId())
                .orElseThrow(BookingNotFoundException::new);
        if (!cancellations.ownsLease(lease)) return BookingView.from(booking);
        if (booking.getStatus() == BookingStatus.CANCEL_PENDING) {
            if (released) {
                booking.completeCancellation();
                cancellations.finish(lease);
            } else {
                boolean exhausted = lease.attempt() >= maxAttempts;
                cancellations.postpone(lease, Instant.now().plus(
                        backoff.delay(lease.bookingId(), lease.attempt())), exhausted);
            }
        } else if (booking.getStatus() == BookingStatus.CANCELLED) {
            cancellations.finish(lease);
        } else {
            throw new IllegalStateException("Cancellation job attached to non-cancelable booking");
        }
        return BookingView.from(booking);
    }

    /** Fencing: a stale worker must not change Booking after losing the lease. */
    @Transactional
    public BookingView settleRecovery(RecoveryLease lease,
            InventoryPort.Attempt attempt, int maxAttempts) {
        BookingEntity booking = bookings.lockById(lease.bookingId())
                .orElseThrow(BookingNotFoundException::new);
        if (!jobs.ownsLease(lease)) return BookingView.from(booking);
        if (booking.getStatus().isTerminal()) {
            jobs.finish(lease);
            return BookingView.from(booking);
        }
        booking.transition(attempt.status(), attempt.reason());
        if (booking.getStatus().isTerminal()) {
            jobs.finish(lease);
        } else {
            boolean exhausted = lease.attempt() >= maxAttempts;
            String reason = exhausted ? "Auto recovery exhausted; manual retry required"
                    : "Inventory outcome unknown";
            jobs.postpone(lease, Instant.now().plus(backoff.delay(lease.bookingId(), lease.attempt())),
                    reason, exhausted);
        }
        return BookingView.from(booking);
    }
}
