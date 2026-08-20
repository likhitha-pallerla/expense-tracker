package com.expensetracker.api.connections;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts OAuth tokens before they reach the database.
 *
 * <p>A refresh token is a permanent, silent key to someone's inbox. Unlike a
 * password it cannot be reset by the user, is not rate-limited, and leaves no
 * trace in their account when used. It is the one value in this system that
 * must never be readable from a database dump, a backup, a log line, or by
 * anyone holding a Postgres connection — which, on a hosted database, includes
 * the provider.
 *
 * <h2>AES-256-GCM, not AES-CBC</h2>
 *
 * GCM authenticates as well as encrypts: a ciphertext altered by a single bit
 * fails to decrypt rather than producing plausible garbage. Without that, an
 * attacker with write access to the row could flip bits and learn from how the
 * application behaved.
 *
 * <h2>The user id is bound into the ciphertext</h2>
 *
 * The user id is passed as additional authenticated data, so a token encrypted
 * for one user cannot be decrypted for another. Copying a row between users —
 * the obvious move for anyone who gains write access but not the key — produces
 * a decryption failure rather than a working mailbox connection.
 *
 * <h2>The envelope carries its version</h2>
 *
 * Stored as {@code v1.<iv>.<ciphertext>}. The version is not decoration: when
 * the key is rotated or the algorithm changes, existing rows must stay readable
 * long enough to re-encrypt them. A bare base64 blob would have to be guessed
 * at.
 */
public final class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String VERSION = "v1";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private TokenCipher(SecretKey key) {
        this.key = key;
    }

    /**
     * Builds a cipher from a base64-encoded 256-bit key.
     *
     * <p>A blank key yields an unconfigured cipher rather than a startup
     * failure. Mailbox syncing is one feature among many; refusing to boot the
     * whole API because it is not set up would take budgets, cards and imports
     * down with it. The failure is raised instead when someone tries to connect
     * a mailbox, where it can be explained.
     */
    public static TokenCipher fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return new TokenCipher(null);
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY is not valid base64. Generate one with: "
                            + "openssl rand -base64 32", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must decode to exactly 32 bytes, got " + raw.length
                            + ". Generate one with: openssl rand -base64 32");
        }
        return new TokenCipher(new SecretKeySpec(raw, "AES"));
    }

    public boolean isConfigured() {
        return key != null;
    }

    /**
     * @param context value bound to the ciphertext, in practice the owning user
     *                id; decryption fails unless the same value is supplied
     */
    public String encrypt(String plaintext, String context) {
        requireConfigured();
        if (plaintext == null) {
            return null;
        }

        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + "." + encoder.encodeToString(iv) + "."
                    + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            // Deliberately vague: a fuller message would describe the key
            // material to whatever is reading the logs.
            throw new IllegalStateException("Could not encrypt token", e);
        }
    }

    public String decrypt(String envelope, String context) {
        requireConfigured();
        if (envelope == null) {
            return null;
        }

        String[] parts = envelope.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("Stored token is not in the expected envelope format");
        }
        if (!VERSION.equals(parts[0])) {
            throw new IllegalStateException(
                    "Stored token uses unsupported encryption version " + parts[0]);
        }

        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored token could not be decrypted", e);
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY is not set, so mailbox tokens cannot be stored safely.");
        }
    }
}
