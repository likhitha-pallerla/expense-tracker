package com.expensetracker.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    private static final String URL = "https://abcdefghijklmnop.supabase.co";

    @Test
    void buildsIssuerFromProjectUrl() {
        assertThat(SecurityConfig.issuerFor(URL))
                .isEqualTo("https://abcdefghijklmnop.supabase.co/auth/v1");
    }

    @Test
    void toleratesTrailingSlashesInProjectUrl() {
        assertThat(SecurityConfig.issuerFor(URL + "///"))
                .isEqualTo("https://abcdefghijklmnop.supabase.co/auth/v1");
    }

    /**
     * Supabase signs access tokens with ES256 and publishes the public key at
     * this path. Verifying against the legacy shared HS256 secret does not work
     * and previously caused every request to 401.
     */
    @Test
    void derivesJwkSetUriRatherThanUsingASharedSecret() {
        assertThat(SecurityConfig.jwkSetUriFor(URL))
                .isEqualTo("https://abcdefghijklmnop.supabase.co/auth/v1/.well-known/jwks.json");
    }
}
