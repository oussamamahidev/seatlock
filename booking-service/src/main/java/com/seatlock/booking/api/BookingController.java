package com.seatlock.booking.api;

import com.seatlock.booking.application.BookingWorkflow;
import com.seatlock.booking.domain.BookingStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identity comes exclusively from the verified access-token sub claim. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
    private final BookingWorkflow workflow;

    public BookingController(BookingWorkflow workflow) {
        this.workflow = workflow;
    }

    @PostMapping
    public ResponseEntity<BookingView> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateBookingRequest request) {
        var outcome = workflow.create(customerId(jwt), key, request);
        return ResponseEntity.status(statusFor(outcome.booking().status(), outcome.created()))
                .body(outcome.booking());
    }

    @GetMapping("/{id}")
    public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workflow.get(customerId(jwt), id);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<BookingView> retry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        BookingView booking = workflow.retry(customerId(jwt), id);
        return ResponseEntity.status(statusFor(booking.status(), false)).body(booking);
    }

    private static UUID customerId(Jwt jwt) {
        // Keycloak user IDs are UUIDs. Never parse X-Demo-User or a request-supplied user ID.
        return UUID.fromString(jwt.getSubject());
    }

    private static HttpStatus statusFor(BookingStatus state, boolean newlyCreated) {
        return switch (state) {
            case CONFIRMED -> newlyCreated ? HttpStatus.CREATED : HttpStatus.OK;
            case REJECTED -> HttpStatus.CONFLICT;
            case PENDING, UNCERTAIN -> HttpStatus.ACCEPTED;
        };
    }
}
