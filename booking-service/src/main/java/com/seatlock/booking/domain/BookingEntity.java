package com.seatlock.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class BookingEntity {
    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "seat_id", nullable = false)
    private UUID seatId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 80)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BookingEntity() { }

    public BookingEntity(UUID id, UUID customerId, UUID eventId, UUID seatId,
                         String idempotencyKey, String requestFingerprint) {
        this(id, customerId, eventId, seatId, idempotencyKey, requestFingerprint, null);
    }

    public BookingEntity(UUID id, UUID customerId, UUID eventId, UUID seatId,
                         String idempotencyKey, String requestFingerprint, UUID holdId) {
        this.holdId = holdId;
        this.id = id;
        this.customerId = customerId;
        this.eventId = eventId;
        this.seatId = seatId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.status = BookingStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void transition(BookingStatus next, String reason) {
        if (this.status.isTerminal() || this.status == BookingStatus.CANCEL_PENDING) {
            return; // A delayed response cannot overwrite a committed decision.
        }
        this.status = next;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    /** Cancellation is a separate Saga, not a normal reservation transition. */
    public void startCancellation() {
        if (status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only confirmed bookings can start cancellation");
        }
        status = BookingStatus.CANCEL_PENDING;
        updatedAt = Instant.now();
    }

    public void completeCancellation() {
        if (status != BookingStatus.CANCEL_PENDING) {
            throw new IllegalStateException("Booking is not awaiting cancellation");
        }
        status = BookingStatus.CANCELLED;
        failureReason = null;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getEventId() { return eventId; }
    public UUID getSeatId() { return seatId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public BookingStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public UUID getHoldId() { return holdId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
