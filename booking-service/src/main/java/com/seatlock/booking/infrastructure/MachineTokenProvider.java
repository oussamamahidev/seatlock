package com.seatlock.booking.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

/** Small dev-friendly client-credentials implementation. No customer token is forwarded. */
@Component
public class MachineTokenProvider {
    private final RestClient tokenClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    public MachineTokenProvider(
            @Value("${security.machine.token-uri}") String tokenUri,
            @Value("${security.machine.client-id}") String clientId,
            @Value("${security.machine.client-secret}") String clientSecret) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.tokenClient = RestClient.builder().requestFactory(factory).build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String bearerToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        TokenResponse response = tokenClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
        if (response == null || response.access_token() == null || response.access_token().isBlank()) {
            throw new IllegalStateException("Keycloak returned no machine access token");
        }
        return response.access_token();
    }

    public record TokenResponse(String access_token, String token_type, long expires_in) { }
}
