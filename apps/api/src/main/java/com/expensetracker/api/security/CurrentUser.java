package com.expensetracker.api.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Resolves the current user's id from the security context.
 *
 * <p>Every query in the service layer filters by this value. The backend
 * connects to Postgres as an owner role that bypasses RLS, so this
 * application-level check is the primary isolation boundary, with RLS as the
 * second line of defence for direct client access.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                try {
                    return UUID.fromString(subject);
                } catch (IllegalArgumentException ex) {
                    throw new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED, "Token subject is not a valid user id");
                }
            }
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
    }

    public static String email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getToken().getClaimAsString("email");
        }
        return null;
    }
}
