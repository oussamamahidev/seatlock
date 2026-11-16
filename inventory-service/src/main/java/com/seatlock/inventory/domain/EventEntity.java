package com.seatlock.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class EventEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 160)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    protected EventEntity() {
        // Required by JPA.
    }

    public EventEntity(UUID id, String title, String venue, Instant startsAt) {
        this.id = id;
        this.title = title;
        this.venue = venue;
        this.startsAt = startsAt;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
}
