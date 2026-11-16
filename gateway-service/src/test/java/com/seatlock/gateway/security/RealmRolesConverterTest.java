package com.seatlock.gateway.security;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class RealmRolesConverterTest {
    private final RealmRolesConverter converter = new RealmRolesConverter();

    @Test
    void mapsRealmRolesAndScopes() {
        Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .subject("customer-uuid")
            .claim("realm_access", Map.of("roles", List.of("customer", "other")))
            .claim("scope", "profile")
            .build();
        assertThat(converter.convert(jwt)).extracting("authority")
            .contains("ROLE_customer", "ROLE_other", "SCOPE_profile");
    }

    @Test
    void missingRealmClaimIsSafe() {
        Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .subject("customer-uuid")
            .build();
        assertThat(converter.convert(jwt)).isEmpty();
    }
}
