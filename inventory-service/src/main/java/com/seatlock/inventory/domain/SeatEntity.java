package com.seatlock.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "seats")
public class SeatEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_seats_event"))
    private EventEntity event;

    @Column(nullable = false, length = 12)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    protected SeatEntity() {
        // Required by JPA.
    }

    public SeatEntity(UUID id, EventEntity event, String label, SeatStatus status) {
        this.id = id;
        this.event = event;
        this.label = label;
        this.status = status;
    }

    public UUID getId() { return id; }
    public EventEntity getEvent() { return event; }
    public String getLabel() { return label; }
    public SeatStatus getStatus() { return status; }
    public UUID getReservationId() { return reservationId; }
}
