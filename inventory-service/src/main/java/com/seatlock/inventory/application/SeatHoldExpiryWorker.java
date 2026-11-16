package com.seatlock.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="seatlock.holds", name="enabled", havingValue="true", matchIfMissing=true)
public class SeatHoldExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiryWorker.class);
    private final SeatHoldService holds;
    public SeatHoldExpiryWorker(SeatHoldService holds) { this.holds = holds; }

    @Scheduled(fixedDelayString = "${seatlock.holds.sweep-ms:2000}")
    public void sweep() {
        try {
            int count = holds.expireDue();
            if (count > 0) log.info("Expired {} temporary seat holds", count);
        } catch (RuntimeException ex) {
            log.error("Seat hold expiration failed", ex);
        }
    }
}
