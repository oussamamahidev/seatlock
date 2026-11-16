package com.seatlock.booking.infrastructure;

import com.seatlock.booking.api.BookingView;
import com.seatlock.booking.application.InventoryPort;
import com.seatlock.booking.domain.BookingStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

@Component
public class RestInventoryAdapter implements InventoryPort, com.seatlock.booking.application.HoldPort {
    private final RestClient client;
    private final MachineTokenProvider tokens;

    public RestInventoryAdapter(@Value("${inventory.base-url}") String inventoryBaseUrl,
                                MachineTokenProvider tokens) {
        this.tokens = tokens;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder()
                .baseUrl(inventoryBaseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Attempt reserve(BookingView booking) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("bookingId", booking.id());
            if (booking.holdId() != null) {
                payload.put("holdId", booking.holdId());
                payload.put("customerId", booking.customerId());
            }
            var response = client.post()
                    .uri("/api/v1/events/{eventId}/seats/{seatId}/reservations",
                            booking.eventId(), booking.seatId())
                    .headers(h -> h.setBearerAuth(tokens.bearerToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(InventoryReservation.class);

            InventoryReservation receipt = response.getBody();
            // Validate the receipt; status alone is not enough to confirm ownership.
            if (receipt == null || !booking.id().equals(receipt.bookingId())
                    || !booking.seatId().equals(receipt.seatId())
                    || !booking.eventId().equals(receipt.eventId())
                    || !"RESERVED".equals(receipt.status())) {
                return new Attempt(BookingStatus.UNCERTAIN, "Invalid inventory receipt");
            }
            return new Attempt(BookingStatus.CONFIRMED, null);
        } catch (HttpClientErrorException.Conflict ex) {
            return new Attempt(BookingStatus.REJECTED, "Seat is unavailable");
        } catch (HttpClientErrorException.NotFound ex) {
            return new Attempt(BookingStatus.REJECTED, "Seat or event does not exist");
        } catch (RestClientException ex) {
            // Could have committed remotely; never assume rollback after a timeout or 5xx.
            return new Attempt(BookingStatus.UNCERTAIN, "Inventory outcome not confirmed");
        }
    }

    @Override
    public void release(BookingView booking) {
        try {
            client.delete()
                    .uri("/api/v1/events/{eventId}/seats/{seatId}/reservations/{bookingId}",
                            booking.eventId(), booking.seatId(), booking.id())
                    .headers(h -> h.setBearerAuth(tokens.bearerToken()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw new IllegalStateException("Inventory release outcome unknown", ex);
        }
    }

    @Override
    public com.seatlock.booking.application.HoldPort.HoldAttempt create(
            UUID eventId, UUID seatId, UUID customerId, UUID holdId) {
        try {
            var response = client.post()
                    .uri("/api/v1/events/{eventId}/seats/{seatId}/holds", eventId, seatId)
                    .headers(h -> h.setBearerAuth(tokens.bearerToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("holdId", holdId, "customerId", customerId))
                    .retrieve().toEntity(com.seatlock.booking.api.HoldView.class);
            var receipt = response.getBody();
            if (receipt == null || !holdId.equals(receipt.holdId())
                    || !eventId.equals(receipt.eventId()) || !seatId.equals(receipt.seatId())
                    || !customerId.equals(receipt.customerId()) || receipt.expiresAt() == null
                    || !"HELD".equals(receipt.status())) {
                throw new com.seatlock.booking.application.HoldUnavailableException(
                        "Inventory returned an invalid hold receipt");
            }
            return new com.seatlock.booking.application.HoldPort.HoldAttempt(
                    receipt, response.getStatusCode().value() == 201);
        } catch (HttpClientErrorException.Conflict ex) {
            throw new com.seatlock.booking.application.HoldConflictException(
                    "Seat unavailable or idempotency key already bound to another seat");
        } catch (HttpClientErrorException.NotFound ex) {
            throw new com.seatlock.booking.application.HoldConflictException("Seat or event not found");
        } catch (RestClientException ex) {
            throw new com.seatlock.booking.application.HoldUnavailableException(
                    "Temporary hold outcome unknown. Retry with the SAME Idempotency-Key", ex);
        }
    }

    @Override
    public void cancel(UUID eventId, UUID seatId, UUID customerId, UUID holdId) {
        try {
            client.delete()
                    .uri(uri -> uri.path("/api/v1/events/{eventId}/seats/{seatId}/holds/{holdId}")
                        .queryParam("customerId", customerId)
                        .build(eventId, seatId, holdId))
                    .headers(h -> h.setBearerAuth(tokens.bearerToken()))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ex) {
            throw new com.seatlock.booking.application.HoldConflictException(
                    "Hold is absent, expired, converted to a reservation, or not owned");
        } catch (RestClientException ex) {
            throw new com.seatlock.booking.application.HoldUnavailableException(
                    "Hold cancellation outcome unknown. Check or retry safely", ex);
        }
    }

    public record InventoryReservation(UUID eventId, UUID seatId, UUID bookingId,
                                        String status) { }
}
