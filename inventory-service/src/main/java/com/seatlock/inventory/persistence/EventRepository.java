package com.seatlock.inventory.persistence;

import com.seatlock.inventory.domain.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventRepository extends JpaRepository<EventEntity, UUID> {
}
