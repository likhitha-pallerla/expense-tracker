package com.expensetracker.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps Supabase JWT claims onto Spring authorities.
 *
 * <p>Supabase puts the application role in {@code role} (typically
 * {@code authenticated}) and any custom roles in {@code app_metadata.roles}.
 * The subject claim is the user's UUID, which is the {@code user_id} used
 * throughout the schema.
 */
public class SupabaseJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    @SuppressWarnings("unchecked")
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        String role = jwt.getClaimAsString("role");
        if (role != null && !role.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        }

        Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
        if (appMetadata != null && appMetadata.get("roles") instanceof List<?> roles) {
            roles.stream()
                 .filter(String.class::isInstance)
                 .map(String.class::cast)
                 .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())));
        }

        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
