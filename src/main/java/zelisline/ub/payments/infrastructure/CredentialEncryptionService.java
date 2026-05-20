package zelisline.ub.payments.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts gateway credentials at rest using AES-256-GCM.
 *
 * <p>The encryption key is loaded from {@code app.payments.encryption-key}
 * (a base64-encoded 256-bit key). If not set, a random key is generated
 * on startup — suitable for development but <strong>not</strong> for
 * production (all encrypted data becomes unreadable on restart).
 *
 * <p>Each encryption produces a {@code base64(IV || ciphertext)} string.
 * The 12-byte GCM IV is prepended to the ciphertext.
 */
@Component
public class CredentialEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int AES_KEY_SIZE = 256;

    private final SecretKey secretKey;
    private final boolean ephemeralKey;

    public CredentialEncryptionService(
            @Value("${app.payments.encryption-key:}") String base64Key
    ) {
        boolean hasConfiguredKey = base64Key != null && !base64Key.isBlank();
        this.ephemeralKey = !hasConfiguredKey;
        this.secretKey = resolveKey(base64Key);
        if (this.ephemeralKey) {
            log.warn(
                    "app.payments.encryption-key is not set — gateway credentials use a random key "
                            + "that changes on every restart. Set APP_PAYMENTS_ENCRYPTION_KEY in production.");
        }
    }

    public boolean usesEphemeralKey() {
        return ephemeralKey;
    }

    /**
     * Encrypt a plaintext string. Returns base64(IV || ciphertext).
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        if (!isPlaintextCredentials(plaintext)) {
            throw new IllegalArgumentException("Refusing to encrypt values that are not plaintext credentials");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt credentials", e);
        }
    }

    /**
     * Decrypt credentials stored at rest. Accepts plaintext JSON left from legacy rows
     * and recovers from accidental double-encryption after a failed connection test.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String trimmed = stored.trim();
        if (isPlaintextCredentials(trimmed)) {
            return trimmed;
        }

        try {
            return decryptCipherBlob(trimmed);
        } catch (Exception firstFailure) {
            try {
                String once = decryptCipherBlob(trimmed);
                if (isPlaintextCredentials(once)) {
                    log.warn("Recovered double-encrypted gateway credentials");
                    return once;
                }
                return decryptCipherBlob(once);
            } catch (Exception secondFailure) {
                String hint = ephemeralKey
                        ? " Payment encryption key is not configured on the server (credentials are lost after restart)."
                        : " Re-save gateway credentials in Payments settings using the same APP_PAYMENTS_ENCRYPTION_KEY as when they were stored.";
                throw new RuntimeException("Failed to decrypt credentials." + hint, firstFailure);
            }
        }
    }

    /** True when the value is JSON credential text, not an encrypted blob. */
    public static boolean isPlaintextCredentials(String value) {
        if (value == null) {
            return false;
        }
        String t = value.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private String decryptCipherBlob(String base64Cipher) throws Exception {
        byte[] data = Base64.getDecoder().decode(base64Cipher);

        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static SecretKey resolveKey(String base64Key) {
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key.trim());
            if (keyBytes.length != AES_KEY_SIZE / 8) {
                throw new IllegalArgumentException(
                        "app.payments.encryption-key must be a base64-encoded 256-bit key");
            }
            return new SecretKeySpec(keyBytes, "AES");
        }
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_SIZE);
            return kg.generateKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate development encryption key", e);
        }
    }
}
