package com.expensetracker.api.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Verifies Supabase-issued JWTs.
 *
 * <p>Supabase signs access tokens asymmetrically with ES256 and publishes the
 * public key at {@code /auth/v1/.well-known/jwks.json}; the token header carries
 * a {@code kid} so keys can be rotated without redeploying. The legacy shared
 * HS256 secret is therefore NOT used for verification.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String AUDIENCE = "authenticated";

    private final String supabaseUrl;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            @Value("${supabase.url}") String supabaseUrl,
            @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.supabaseUrl = supabaseUrl.replaceAll("/+$", "");
        this.allowedOrigins = List.of(allowedOrigins.split(","));
    }

    /** Token issuer for a Supabase project, tolerant of a trailing slash. */
    static String issuerFor(String supabaseUrl) {
        return supabaseUrl.replaceAll("/+$", "") + "/auth/v1";
    }

    /** Public key set used to verify ES256 signatures. */
    static String jwkSetUriFor(String supabaseUrl) {
        return issuerFor(supabaseUrl) + "/.well-known/jwks.json";
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info", "/api/health").permitAll()
                // The OAuth callback is a browser navigation from Google or
                // Microsoft; it cannot carry our Authorization header. It
                // identifies the user by looking up the one-time state it was
                // issued with instead.
                .requestMatchers(HttpMethod.GET, "/api/connections/callback/*").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new SupabaseJwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String issuer = issuerFor(supabaseUrl);

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(jwkSetUriFor(supabaseUrl))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(AUDIENCE));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator));

        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
