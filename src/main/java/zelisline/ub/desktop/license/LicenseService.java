package zelisline.ub.desktop.license;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Creates and verifies Ed25519‑signed license tokens for the desktop SKU
 * (see {@code DESKTOP_INSTALLATION.md} §10).
 *
 * <h2>Token format</h2>
 * {@code base64url(json).base64url(signature)}
 *
 * <h2>Key management</h2>
 * <ul>
 *   <li>The <em>private key</em> lives only on the vendor's admin machine —
 *       it never ships with the product.</li>
 *   <li>The <em>public key</em> is baked into the JAR (or supplied via
 *       {@code APP_DESKTOP_LICENSE_PUBLIC_KEY} env var) and is the <em>only</em>
 *       key this service can verify against.</li>
 * </ul>
 *
 * <h2>Trial mode</h2>
 * When no license key is set, the system runs in a 30‑day trial. The trial
 * starts from the {@code .initialized} file's {@code setup_completed_at}
 * timestamp. After the trial expires the UI degrades to read‑only.
 */
@Service
@Profile("desktop")
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(
        LicenseService.class
    );

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(
        new JavaTimeModule()
    );

    private static final int TRIAL_DAYS = 30;

    private final PublicKey publicKey;
    private final Path initializedFile;

    public LicenseService(
        @Value("${app.desktop.license.public-key:}") String publicKeyBase64
    ) {
        // Resolve APP_DATA directly
        this.initializedFile = Path.of(
            System.getenv().getOrDefault(
                "APP_DATA",
                System.getProperty("user.home") + "/.palmart"
            ),
            ".initialized"
        );

        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            log.warn(
                "[License] no public key configured — trial-only mode. " +
                    "Set APP_DESKTOP_LICENSE_PUBLIC_KEY to enable license verification."
            );
            this.publicKey = null;
        } else {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(
                    publicKeyBase64.trim()
                );
                this.publicKey = KeyFactory.getInstance(
                    "Ed25519"
                ).generatePublic(new X509EncodedKeySpec(keyBytes));
                log.info("[License] public key loaded (Ed25519)");
            } catch (Exception e) {
                throw new RuntimeException(
                    "Invalid license public key. " +
                        "Ensure APP_DESKTOP_LICENSE_PUBLIC_KEY is a valid base64-encoded Ed25519 public key.",
                    e
                );
            }
        }
    }

    // ========================================================================
    // TOKEN FORMAT
    // ========================================================================

    /**
     * Encodes a license payload + Ed25519 signature into the compact token
     * format: {@code base64url(payload).base64url(signature)}.
     *
     * <p>This is the <em>vendor‑side</em> operation — called by the admin tool,
     * never at runtime.
     */
    public static String encodeToken(
        LicensePayload payload,
        PrivateKey privateKey
    ) {
        try {
            byte[] payloadBytes = JSON.writeValueAsBytes(payload);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(payloadBytes);
            byte[] signatureBytes = sig.sign();

            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            return (
                enc.encodeToString(payloadBytes) +
                "." +
                enc.encodeToString(signatureBytes)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode license token", e);
        }
    }

    /**
     * Decodes a compact token, verifies the signature, and returns the payload.
     * Returns {@code null} if the signature is invalid or the token is malformed.
     */
    public LicensePayload decodeAndVerify(String token) {
        if (publicKey == null) {
            log.warn("[License] cannot verify — no public key configured");
            return null;
        }
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            int dot = token.lastIndexOf('.');
            if (dot < 1 || dot >= token.length() - 1) {
                return null;
            }

            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] payloadBytes = dec.decode(token.substring(0, dot));
            byte[] signatureBytes = dec.decode(token.substring(dot + 1));

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(payloadBytes);

            if (!sig.verify(signatureBytes)) {
                log.warn("[License] signature verification failed");
                return null;
            }

            return JSON.readValue(payloadBytes, LicensePayload.class);
        } catch (Exception e) {
            log.warn(
                "[License] failed to decode/verify token: {}",
                e.getMessage()
            );
            return null;
        }
    }

    // ========================================================================
    // RUNTIME STATUS
    // ========================================================================

    /**
     * Computes the current license status for the given token and business name.
     *
     * @param token        the compact license token (may be null for trial mode)
     * @param businessName the installed business name (must match the license)
     * @return a status object the frontend can render
     */
    public LicenseStatus checkStatus(String token, String businessName) {
        // ── Licensed mode ──────────────────────────────────────────────
        if (token != null && !token.isBlank()) {
            LicensePayload payload = decodeAndVerify(token);
            if (payload == null) {
                return LicenseStatus.invalid(
                    "The license signature is invalid or the token is corrupt."
                );
            }

            if (
                businessName != null &&
                !businessName.equals(payload.businessName())
            ) {
                return LicenseStatus.invalid(
                    "This license was issued to '" +
                        payload.businessName() +
                        "', not '" +
                        businessName +
                        "'."
                );
            }

            if (
                payload.expiresAt() != null &&
                payload.expiresAt().isBefore(Instant.now())
            ) {
                return LicenseStatus.expired(
                    payload.plan(),
                    payload.expiresAt()
                );
            }

            long days =
                payload.expiresAt() != null
                    ? ChronoUnit.DAYS.between(
                          Instant.now(),
                          payload.expiresAt()
                      )
                    : Long.MAX_VALUE;
            return LicenseStatus.valid(
                payload.plan(),
                payload.expiresAt(),
                days
            );
        }

        // ── Trial mode ────────────────────────────────────────────────
        if (!Files.exists(initializedFile)) {
            // Setup hasn't happened yet — no trial to check.
            return LicenseStatus.trialActive(TRIAL_DAYS);
        }

        try {
            String content = Files.readString(
                initializedFile,
                StandardCharsets.UTF_8
            );
            Instant setupAt = null;
            for (String line : content.split("\n")) {
                if (line.startsWith("setup_completed_at=")) {
                    setupAt = Instant.parse(
                        line.substring("setup_completed_at=".length()).trim()
                    );
                    break;
                }
            }
            if (setupAt == null) {
                return LicenseStatus.trialActive(TRIAL_DAYS);
            }

            long daysSinceSetup = ChronoUnit.DAYS.between(
                setupAt,
                Instant.now()
            );
            long daysRemaining = TRIAL_DAYS - daysSinceSetup;

            if (daysRemaining <= 0) {
                return LicenseStatus.trialExpired(
                    setupAt.plus(TRIAL_DAYS, ChronoUnit.DAYS)
                );
            }

            return LicenseStatus.trialActive(daysRemaining);
        } catch (IOException e) {
            log.warn(
                "[License] could not read .initialized file: {}",
                e.getMessage()
            );
            return LicenseStatus.trialActive(TRIAL_DAYS);
        }
    }

    // ========================================================================
    // KEY GENERATION (vendor‑side tooling)
    // ========================================================================

    /**
     * Generates a fresh Ed25519 key pair. Used by the vendor's admin tool.
     * The private key must be kept secret; the public key's base64 form is
     * pasted into {@code APP_DESKTOP_LICENSE_PUBLIC_KEY}.
     */
    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to generate Ed25519 key pair",
                e
            );
        }
    }

    /** Base64‑encodes an X.509 public key for the env var / properties file. */
    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Base64‑encodes a PKCS#8 private key (keep this secret!). */
    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Decodes a base64‑encoded PKCS#8 private key. */
    public static PrivateKey decodePrivateKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(bytes)
            );
        } catch (Exception e) {
            throw new RuntimeException("Invalid private key", e);
        }
    }

    /** Decodes a base64‑encoded X.509 public key. */
    public static PublicKey decodePublicKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(bytes)
            );
        } catch (Exception e) {
            throw new RuntimeException("Invalid public key", e);
        }
    }
}
