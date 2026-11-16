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
import java.util.UUID;

@Component
public class RestInventoryAdapter implements InventoryPort {
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
            var response = client.post()
                    .uri("/api/v1/events/{eventId}/seats/{seatId}/reservations",
                            booking.eventId(), booking.seatId())
                    .headers(h -> h.setBearerAuth(tokens.bearerToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("bookingId", booking.id()))
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

    public record InventoryReservation(UUID eventId, UUID seatId, UUID bookingId,
                                        String status) { }
}
