package com.seatlock.inventory.persistence;

import com.seatlock.inventory.domain.SeatEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<SeatEntity, UUID> {
    Page<SeatEntity> findByEvent_Id(UUID eventId, Pageable pageable);

    Optional<SeatEntity> findByReservationId(UUID bookingId);
    Optional<SeatEntity> findByHoldId(UUID holdId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats SET status = 'HELD', hold_id = :holdId,
                hold_customer_id = :customerId,
                hold_expires_at = now() + (:ttlSeconds * interval '1 second'),
                reservation_id = NULL
            WHERE id = :seatId AND event_id = :eventId
              AND (status = 'AVAILABLE' OR
                   (status = 'HELD' AND hold_expires_at <= now()))
            """, nativeQuery = true)
    int holdIfAvailable(@Param("eventId") UUID eventId,
                        @Param("seatId") UUID seatId,
                        @Param("holdId") UUID holdId,
                        @Param("customerId") UUID customerId,
                        @Param("ttlSeconds") int ttlSeconds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats SET status = 'AVAILABLE', hold_id = NULL,
                hold_customer_id = NULL, hold_expires_at = NULL
            WHERE id = :seatId AND event_id = :eventId AND status = 'HELD'
              AND hold_id = :holdId AND hold_customer_id = :customerId
            """, nativeQuery = true)
    int releaseOwnedHold(@Param("eventId") UUID eventId,
                         @Param("seatId") UUID seatId,
                         @Param("holdId") UUID holdId,
                         @Param("customerId") UUID customerId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats SET status = 'AVAILABLE', hold_id = NULL,
                hold_customer_id = NULL, hold_expires_at = NULL
            WHERE status = 'HELD' AND hold_expires_at <= now()
            """, nativeQuery = true)
    int expireDueHolds();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats SET status = 'RESERVED', reservation_id = :bookingId,
                hold_id = NULL, hold_customer_id = NULL, hold_expires_at = NULL
            WHERE id = :seatId AND event_id = :eventId
              AND status = 'HELD' AND hold_id = :holdId
              AND hold_customer_id = :customerId AND hold_expires_at > now()
              AND reservation_id IS NULL
            """, nativeQuery = true)
    int claimOwnedHold(@Param("eventId") UUID eventId,
                       @Param("seatId") UUID seatId,
                       @Param("bookingId") UUID bookingId,
                       @Param("holdId") UUID holdId,
                       @Param("customerId") UUID customerId);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
            SET status = 'RESERVED', reservation_id = :bookingId,
                hold_id = NULL, hold_customer_id = NULL, hold_expires_at = NULL
            WHERE id = :seatId
              AND event_id = :eventId
              AND (status = 'AVAILABLE' OR
                   (status = 'HELD' AND hold_expires_at <= now()))
              AND reservation_id IS NULL
            """, nativeQuery = true)
    int claimIfAvailable(@Param("eventId") UUID eventId,
                         @Param("seatId") UUID seatId,
                         @Param("bookingId") UUID bookingId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats SET status='AVAILABLE', reservation_id=NULL
            WHERE id=:seatId AND event_id=:eventId AND status='RESERVED'
              AND reservation_id=:bookingId
            """, nativeQuery = true)
    int releaseReservationIfOwned(@Param("eventId") UUID eventId,
                                  @Param("seatId") UUID seatId,
                                  @Param("bookingId") UUID bookingId);

}
