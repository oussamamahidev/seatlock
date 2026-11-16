package com.seatlock.booking.application;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.seatlock.booking.domain.BookingStatus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;

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
    private final Counter polls;
    private final Counter claimed;
    private final Counter claimErrors;
    private final Counter processingErrors;
    private final Timer duration;
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
        // Register at startup: an idle worker is observable before its first job.
        this.polls = metrics.counter("seatlock.recovery.polls");
        this.claimed = metrics.counter("seatlock.recovery.claimed");
        this.claimErrors = metrics.counter("seatlock.recovery.claim.errors");
        this.processingErrors = metrics.counter("seatlock.recovery.errors");
        this.duration = Timer.builder("seatlock.recovery.duration").register(metrics);
        for (BookingStatus status : BookingStatus.values()) {
            metrics.counter("seatlock.recovery.outcomes", "status", status.name());
        }
    }

    @Scheduled(initialDelayString = "${seatlock.recovery.initial-delay-ms:8000}",
            fixedDelayString = "${seatlock.recovery.poll-interval-ms:2000}")
    public void poll() {
        polls.increment();
        List<RecoveryLease> leases;
        try {
            leases = jobs.claim(batchSize, leaseSeconds);
        } catch (RuntimeException ex) {
            claimErrors.increment();
            log.error("Cannot claim recovery jobs; next scheduled poll will retry", ex);
            return;
        }
        claimed.increment(leases.size());
        if (!leases.isEmpty()) log.info("Claimed {} recovery job(s)", leases.size());
        for (RecoveryLease lease : leases) process(lease);
    }

    private void process(RecoveryLease lease) {
        long start = System.nanoTime();
        try {
            var booking = store.internalBooking(lease.bookingId());
            InventoryPort.Attempt result;
            if (booking.status().isTerminal()) {
                // settleRecovery will acknowledge a terminal booking without a remote call.
                result = new InventoryPort.Attempt(booking.status(), null);
            } else {
                try {
                    result = inventory.reserve(booking);
                } catch (RuntimeException ex) {
                    // Authentication, DNS, HTTP, Keycloak failures do not imply inventory rollback.
                    log.warn("Recovery attempt for booking {} unavailable",
                            lease.bookingId(), ex);
                    result = new InventoryPort.Attempt(BookingStatus.UNCERTAIN,
                            "Inventory outcome unknown");
                }
            }
            BookingStatus status = store.settleRecovery(lease, result, maxAttempts).status();
            metrics.counter("seatlock.recovery.outcomes", "status", status.name()).increment();
            log.info("Recovery booking={} attempt={} status={}", lease.bookingId(), lease.attempt(), status);
        } catch (RuntimeException ex) {
            // No acknowledgement on local failure: let the lease expire for another worker.
            processingErrors.increment();
            log.error("Cannot process recovery booking=" + lease.bookingId()
                    + " attempt=" + lease.attempt() + "; lease will expire", ex);
        } finally {
            duration.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}