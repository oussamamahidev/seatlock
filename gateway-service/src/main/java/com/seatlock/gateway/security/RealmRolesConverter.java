package com.seatlock.gateway.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Convert Keycloak realm_access.roles to Spring authorities; preserve scopes. */
public final class RealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> result = new ArrayList<>(scopes.convert(jwt));
        Object realmClaim = jwt.getClaim("realm_access");
        if (!(realmClaim instanceof Map<?, ?> realm)) {
            return result;
        }
        Object rolesClaim = realm.get("roles");
        if (rolesClaim instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && !name.isBlank()) {
                    result.add(new SimpleGrantedAuthority("ROLE_" + name));
                }
            }
        }
        return result;
    }
}
