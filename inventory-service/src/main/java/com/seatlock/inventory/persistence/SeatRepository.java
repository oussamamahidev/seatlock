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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
            SET status = 'RESERVED', reservation_id = :bookingId
            WHERE id = :seatId
              AND event_id = :eventId
              AND status = 'AVAILABLE'
              AND reservation_id IS NULL
            """, nativeQuery = true)
    int claimIfAvailable(@Param("eventId") UUID eventId,
                         @Param("seatId") UUID seatId,
                         @Param("bookingId") UUID bookingId);
}
