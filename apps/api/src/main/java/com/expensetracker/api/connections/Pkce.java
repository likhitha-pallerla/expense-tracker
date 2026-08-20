package com.expensetracker.api.connections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Proof Key for Code Exchange (RFC 7636).
 *
 * <p>The authorisation code comes back through the user's browser, in a URL,
 * which lands in history, in referrer headers, and in any proxy log along the
 * way. PKCE makes a captured code useless on its own: the token request must
 * also carry the verifier, which never leaves this server.
 *
 * <p>This flow has a client secret as well, so PKCE is not strictly required.
 * It is used anyway because the two protect against different things — the
 * secret proves which application is asking, the verifier proves it is the same
 * session that started — and because a code interception is invisible to
 * everyone involved.
 */
public final class Pkce {

    /**
     * 32 random bytes, base64url-encoded to 43 characters.
     *
     * <p>The RFC permits 43 to 128 characters. The lower bound is chosen
     * deliberately: it is exactly 256 bits of entropy, and every character
     * beyond it adds length to a URL without adding security.
     */
    private static final int VERIFIER_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {
    }

    public static String newVerifier() {
        byte[] raw = new byte[VERIFIER_BYTES];
        RANDOM.nextBytes(raw);
        return ENCODER.encodeToString(raw);
    }

    /** An unguessable value for the {@code state} parameter. */
    public static String newState() {
        byte[] raw = new byte[VERIFIER_BYTES];
        RANDOM.nextBytes(raw);
        return ENCODER.encodeToString(raw);
    }

    /**
     * The S256 challenge: base64url of the SHA-256 of the verifier's ASCII
     * bytes.
     *
     * <p>The {@code plain} method in the same RFC sends the verifier itself and
     * protects against nothing, since anyone who can read the redirect can read
     * both halves. It is not implemented here.
     */
    public static String challengeFor(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }
}
