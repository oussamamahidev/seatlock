package com.seatlock.inventory.api;

import com.seatlock.inventory.application.InventoryQueryService;
import com.seatlock.inventory.application.SeatReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.seatlock.inventory.application.SeatHoldService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class InventoryController {
    private final InventoryQueryService service;
    private final SeatReservationService reservations;
    private final SeatHoldService holds;

    public InventoryController(InventoryQueryService service,
                               SeatReservationService reservations, SeatHoldService holds) {
        this.service = service;
        this.reservations = reservations;
        this.holds = holds;
    }

    @GetMapping
    public PageResponse<EventView> events(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.events(page, size);
    }

    @PostMapping("/{eventId}/seats/{seatId}/reservations")
    public ResponseEntity<ReservationView> reserve(
            @PathVariable UUID eventId,
            @PathVariable UUID seatId,
            @Valid @RequestBody ReserveSeatRequest request) {
        var result = reservations.reserve(eventId, seatId, request.bookingId(),
                request.holdId(), request.customerId());
        var response = new ReservationView(
                result.eventId(), result.seatId(), result.bookingId(), "RESERVED");
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(response);
    }

    @DeleteMapping("/{eventId}/seats/{seatId}/reservations/{bookingId}")
    public ResponseEntity<Void> cancelReservation(@PathVariable UUID eventId,
            @PathVariable UUID seatId, @PathVariable UUID bookingId) {
        reservations.release(eventId, seatId, bookingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{eventId}/seats/{seatId}/holds")
    public ResponseEntity<SeatHoldView> hold(@PathVariable UUID eventId,
             @PathVariable UUID seatId, @Valid @RequestBody HoldSeatRequest request) {
        var outcome = holds.create(eventId, seatId, request.holdId(), request.customerId());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.receipt());
    }

    @DeleteMapping("/{eventId}/seats/{seatId}/holds/{holdId}")
    public ResponseEntity<Void> cancelHold(@PathVariable UUID eventId, @PathVariable UUID seatId,
             @PathVariable UUID holdId, @RequestParam UUID customerId) {
        holds.cancel(eventId, seatId, holdId, customerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{eventId}/seats")
    public PageResponse<SeatView> seats(
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.seats(eventId, page, size);
    }
}
