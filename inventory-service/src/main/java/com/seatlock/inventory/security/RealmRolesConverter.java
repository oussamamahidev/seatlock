package com.seatlock.inventory.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Keycloak realm_access.roles -> ROLE_*, while preserving OAuth2 scope authorities. */
public final class RealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> granted = new ArrayList<>(scopes.convert(jwt));
        Object access = jwt.getClaim("realm_access");
        if (!(access instanceof Map<?, ?> realm)) {
            return granted;
        }
        Object rolesValue = realm.get("roles");
        if (rolesValue instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && !name.isBlank()) {
                    granted.add(new SimpleGrantedAuthority("ROLE_" + name));
                }
            }
        }
        return granted;
    }
}
