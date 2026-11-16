package com.seatlock.booking.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;

class RealmRolesConverterTest {
    private final RealmRolesConverter converter = new RealmRolesConverter();

    @Test void mapsRealmRoleAndPreservesScope() {
        var jwt = token(Map.of("realm_access", Map.of("roles", List.of("customer")), "scope", "read"));
        assertThat(converter.convert(jwt)).extracting("authority")
            .contains("ROLE_customer", "SCOPE_read");
    }

    @Test void doesNotMakeUpRoles() {
        var jwt = token(Map.of("scope", "openid"));
        assertThat(converter.convert(jwt)).extracting("authority")
            .doesNotContain("ROLE_customer", "ROLE_inventory.reserve");
    }

    @Test void serviceAccountRoleIsMappedWithoutGrantingCustomerRole() {
        var jwt = token(Map.of("realm_access", Map.of("roles", List.of("inventory.reserve"))));
        assertThat(converter.convert(jwt)).extracting("authority")
            .contains("ROLE_inventory.reserve").doesNotContain("ROLE_customer");
    }

    private static Jwt token(Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("test.jwt.token")
                .header("alg", "RS256").subject("11111111-1111-4111-8111-111111111111")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
