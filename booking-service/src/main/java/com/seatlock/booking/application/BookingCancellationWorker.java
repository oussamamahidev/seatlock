package com.seatlock.booking.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Separate Saga worker; reserve retries and compensating release never interfere. */
@Component
@ConditionalOnProperty(prefix="seatlock.cancellation", name="enabled", havingValue="true", matchIfMissing=true)
public class BookingCancellationWorker {
    private static final Logger log=LoggerFactory.getLogger(BookingCancellationWorker.class);
    private final CancellationJobStore jobs;
    private final BookingStore store;
    private final InventoryPort inventory;
    private final MeterRegistry metrics;
    private final int maxAttempts;

    public BookingCancellationWorker(CancellationJobStore jobs, BookingStore store,
            InventoryPort inventory, MeterRegistry metrics,
            @Value("${seatlock.cancellation.max-attempts:12}") int maxAttempts) {
        this.jobs=jobs;this.store=store;this.inventory=inventory;this.metrics=metrics;
        if(maxAttempts<1) throw new IllegalArgumentException("maxAttempts must be >=1");
        this.maxAttempts=maxAttempts;
    }

    @Scheduled(initialDelayString="${seatlock.cancellation.initial-delay-ms:3000}",
               fixedDelayString="${seatlock.cancellation.poll-ms:2000}")
    public void poll() {
        List<CancellationLease> claimed;
        try { claimed=jobs.claim(5,90); }
        catch(RuntimeException ex){ log.error("Cannot claim cancellation jobs",ex);return; }
        for(var lease:claimed) {
            boolean released=false;
            try {
                var booking=store.internalBooking(lease.bookingId());
                if (booking.status()==com.seatlock.booking.domain.BookingStatus.CANCELLED)
                    released=true;
                else {
                    inventory.release(booking);
                    released=true;
                }
            } catch(RuntimeException ex) {
                log.warn("Cancellation release outcome unknown for booking {}: {}",
                        lease.bookingId(),ex.getClass().getSimpleName());
            }
            try {
                var updated=store.settleCancellation(lease,released,maxAttempts);
                metrics.counter("seatlock.cancellation.outcomes","status",updated.status().name()).increment();
            } catch(RuntimeException ex) {
                log.error("Cancellation settlement failed for booking {}",lease.bookingId(),ex);
                metrics.counter("seatlock.cancellation.errors").increment();
            }
        }
    }
}
