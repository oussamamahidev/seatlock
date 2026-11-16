package com.seatlock.booking.application;

import com.seatlock.booking.domain.BookingStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** An at-least-once Saga recovery worker; InventoryPort is idempotent by booking UUID. */
@Component
@ConditionalOnProperty(prefix = "seatlock.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BookingRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingRecoveryWorker.class);
    private final RecoveryJobStore jobs;
    private final BookingStore store;
    private final InventoryPort inventory;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;

    public BookingRecoveryWorker(RecoveryJobStore jobs, BookingStore store,
            InventoryPort inventory, MeterRegistry metrics,
            @Value("${seatlock.recovery.batch-size:5}") int batchSize,
            @Value("${seatlock.recovery.lease-seconds:90}") int leaseSeconds,
            @Value("${seatlock.recovery.max-attempts:12}") int maxAttempts) {
        if (batchSize < 1 || batchSize > 100 || leaseSeconds < 10 || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid recovery worker configuration");
        }
        this.jobs = jobs;
        this.store = store;
        this.inventory = inventory;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(initialDelayString = "${seatlock.recovery.initial-delay-ms:8000}",
            fixedDelayString = "${seatlock.recovery.poll-interval-ms:2000}")
    public void poll() {
        List<RecoveryLease> leases;
        try {
            leases = jobs.claim(batchSize, leaseSeconds);
        } catch (RuntimeException ex) {
            log.warn("Cannot claim recovery jobs: {}", ex.getClass().getSimpleName());
            return;
        }
        for (RecoveryLease lease : leases) process(lease);
    }

    private void process(RecoveryLease lease) {
        long start = System.nanoTime();
        BookingStatus status = BookingStatus.UNCERTAIN;
        try {
            var booking = store.internalBooking(lease.bookingId());
            if (booking.status().isTerminal()) {
                // settleRecovery will acknowledge a terminal booking without a remote call.
                status = booking.status();
            } else {
                InventoryPort.Attempt result;
                try {
                    result = inventory.reserve(booking);
                } catch (RuntimeException ex) {
                    // Authentication, DNS, HTTP, Keycloak failures do not imply inventory rollback.
                    log.warn("Recovery attempt for booking {} unavailable: {}",
                            lease.bookingId(), ex.getClass().getSimpleName());
                    result = new InventoryPort.Attempt(BookingStatus.UNCERTAIN,
                            "Inventory outcome unknown");
                }
                status = store.settleRecovery(lease, result, maxAttempts).status();
                metrics.counter("seatlock.recovery.outcomes", "status", status.name()).increment();
                return;
            }
            store.settleRecovery(lease,
                    new InventoryPort.Attempt(status, null), maxAttempts);
            metrics.counter("seatlock.recovery.outcomes", "status", status.name()).increment();
        } catch (RuntimeException ex) {
            // Keep the lease until expiration; no acknowledgement on local DB failure.
            log.error("Recovery job {} could not be completed: {}",
                    lease.bookingId(), ex.getClass().getSimpleName());
            metrics.counter("seatlock.recovery.errors").increment();
        } finally {
            Timer.builder("seatlock.recovery.duration").register(metrics)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
