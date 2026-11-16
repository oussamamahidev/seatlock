package com.seatlock.booking.application;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.domain.BookingEntity;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.booking.persistence.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** Database transactions only. Never performs a network call while holding a DB lock. */
@Service
public class BookingStore {
    private final BookingRepository bookings;

    public BookingStore(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @Transactional(readOnly = true)
    public Optional<BookingView> byCustomerAndKey(UUID customerId, String key) {
        return bookings.findByCustomerIdAndIdempotencyKey(customerId, key)
                .map(BookingView::from);
    }

    @Transactional(readOnly = true)
    public BookingView ownedBooking(UUID customerId, UUID bookingId) {
        return bookings.findByIdAndCustomerId(bookingId, customerId)
                .map(BookingView::from)
                .orElseThrow(BookingNotFoundException::new);
    }

    @Transactional
    public BookingView insert(UUID customerId, String key,
                              UUID eventId, UUID seatId, String fingerprint) {
        BookingEntity booking = new BookingEntity(
                UUID.randomUUID(), customerId, eventId, seatId, key, fingerprint);
        // Flush now so a competing same-key INSERT fails inside this transaction.
        return BookingView.from(bookings.saveAndFlush(booking));
    }

    @Transactional
    public BookingView transition(UUID bookingId, BookingStatus status, String reason) {
        BookingEntity booking = bookings.lockById(bookingId)
                .orElseThrow(BookingNotFoundException::new);
        booking.transition(status, reason);
        return BookingView.from(booking);
    }
}
