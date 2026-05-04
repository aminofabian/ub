package zelisline.ub.integrations.backup.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.backup.config.BackupProperties;

/**
 * AES-256-GCM with PBKDF2-HMAC-SHA256 key derivation. File format:
 * {@code UBBK1 || salt(16) || nonce(12) || ciphertext+tag}.
 */
@Service
@RequiredArgsConstructor
public class BackupEncryptionService {

    private static final int SALT_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;
    private static final String MAGIC = "UBBK1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BackupProperties properties;

    public boolean isReady() {
        return StringUtils.hasText(properties.getEncryption().getPassphrase());
    }

    public void encryptFile(Path plaintextPath, Path destinationPath) throws IOException, GeneralSecurityException {
        if (!isReady()) {
            throw new IllegalStateException("app.integrations.backup.encryption.passphrase is not set");
        }
        byte[] plain = Files.readAllBytes(plaintextPath);
        byte[] enc = encryptBytes(plain);
        Files.write(destinationPath, enc);
    }

    /** For operators / runbook verification — decrypt our artefact to a scratch file. */
    public void decryptFile(Path encryptedPath, Path destinationPlainPath) throws IOException, GeneralSecurityException {
        if (!isReady()) {
            throw new IllegalStateException("app.integrations.backup.encryption.passphrase is not set");
        }
        byte[] cipherBytes = Files.readAllBytes(encryptedPath);
        byte[] plain = decryptBytes(cipherBytes);
        Files.write(destinationPlainPath, plain);
    }

    /** Exposed for tests without filesystem. */
    byte[] encryptBytes(byte[] plaintext) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        SecretKey key = deriveKey(salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] body = cipher.doFinal(plaintext);
        ByteBuffer out =
                ByteBuffer.allocate(MAGIC.length() + SALT_LEN + NONCE_LEN + body.length);
        out.put(MAGIC.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.put(salt);
        out.put(nonce);
        out.put(body);
        return out.array();
    }

    byte[] decryptBytes(byte[] fileBytes) throws GeneralSecurityException {
        ByteBuffer buf = ByteBuffer.wrap(fileBytes);
        byte[] magic = new byte[MAGIC.length()];
        buf.get(magic);
        if (!MAGIC.equals(new String(magic, java.nio.charset.StandardCharsets.UTF_8))) {
            throw new GeneralSecurityException("Unrecognized backup envelope (missing UBBK1 magic)");
        }
        byte[] salt = new byte[SALT_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        buf.get(salt);
        buf.get(nonce);
        byte[] rest = new byte[buf.remaining()];
        buf.get(rest);
        SecretKey key = deriveKey(salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(rest);
    }

    private SecretKey deriveKey(byte[] salt) throws GeneralSecurityException {
        String passphrase = properties.getEncryption().getPassphrase();
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec =
                new PBEKeySpec(passphrase.toCharArray(), salt, properties.getEncryption().getPbkdf2Iterations(), AES_KEY_BITS);
        SecretKey tmp = skf.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }
}
