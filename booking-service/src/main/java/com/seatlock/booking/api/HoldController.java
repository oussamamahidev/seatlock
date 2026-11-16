package com.seatlock.booking.api;

import com.seatlock.booking.application.HoldWorkflow;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Browser never calls Inventory machine-only APIs. Customer identity is verified JWT sub. */
@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {
    private final HoldWorkflow workflow;
    public HoldController(HoldWorkflow workflow) { this.workflow = workflow; }

    @PostMapping
    public ResponseEntity<HoldView> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateHoldRequest request) {
        var outcome = workflow.create(UUID.fromString(jwt.getSubject()), key, request);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.receipt());
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID holdId, @RequestParam UUID eventId, @RequestParam UUID seatId) {
        workflow.cancel(UUID.fromString(jwt.getSubject()), eventId, seatId, holdId);
        return ResponseEntity.noContent().build();
    }
}
