package zelisline.ub.desktop.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
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
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <li>The <em>private key</em> lives only on the vendor's side — the Super
 *       Admin console (deployment env var {@code APP_DESKTOP_LICENSE_PRIVATE_KEY}
 *       or the console-managed key) — it never ships with the product.</li>
 *   <li>At runtime the till verifies against the console's <em>current</em>
 *       signing public key, synced from the platform by {@link
 *       DesktopLicenseKeySyncer} while online. The public key baked into the JAR
 *       ({@code app.desktop.license.public-key} / {@code
 *       APP_DESKTOP_LICENSE_PUBLIC_KEY} env var) is the offline fallback used
 *       until the first sync — and the only key for standalone CLI {@code verify}
 *       runs.</li>
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

    /** DER prefix for a raw 32-byte Ed25519 public key (OID 1.3.101.112). */
    private static final byte[] ED25519_SPKI_PREFIX = new byte[] {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    /** Public key baked into the app ({@code app.desktop.license.public-key}). */
    private final PublicKey bakedPublicKey;

    /**
     * Console-synced verification key pushed by {@link DesktopLicenseKeySyncer}
     * (null until the first sync — always null in standalone CLI/test mode).
     * Takes precedence over the baked key because it matches the key the vendor
     * is actually signing with.
     */
    private volatile PublicKey syncedPublicKey;

    private final MachineFingerprintProvider machineFingerprintProvider;
    private final Path initializedFile;

    /**
     * Spring wiring (desktop profile): binds the till's machine fingerprint.
     */
    @Autowired
    public LicenseService(
        @Value("${app.desktop.license.public-key:}") String publicKeyBase64,
        MachineFingerprintProvider machineFingerprintProvider
    ) {
        this(publicKeyBase64, machineFingerprintProvider, resolveInitializedFile());
    }

    /**
     * Standalone verifier (vendor CLI {@code verify}, tests) — no machine
     * binding is enforced because there is no local machine identity here.
     */
    public LicenseService(String publicKeyBase64) {
        this(publicKeyBase64, null, resolveInitializedFile());
    }

    private LicenseService(
        String publicKeyBase64,
        MachineFingerprintProvider machineFingerprintProvider,
        Path initializedFile
    ) {
        this.machineFingerprintProvider = machineFingerprintProvider;
        this.initializedFile = initializedFile;

        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            log.warn(
                "[License] no baked public key configured — trial-only until a key " +
                    "is baked (APP_DESKTOP_LICENSE_PUBLIC_KEY) or synced from the " +
                    "Super Admin console."
            );
            this.bakedPublicKey = null;
        } else {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(
                    publicKeyBase64.trim()
                );
                this.bakedPublicKey = KeyFactory.getInstance(
                    "Ed25519"
                ).generatePublic(new X509EncodedKeySpec(keyBytes));
                log.info("[License] baked public key loaded (Ed25519)");
            } catch (Exception e) {
                throw new RuntimeException(
                    "Invalid license public key. " +
                        "Ensure APP_DESKTOP_LICENSE_PUBLIC_KEY is a valid base64-encoded Ed25519 public key.",
                    e
                );
            }
        }
    }

    /**
     * Installs the console-synced verification key (called by {@link
     * DesktopLicenseKeySyncer}). Takes precedence over the baked key. Pass
     * {@code null} to clear — never done at runtime: the till keeps the last
     * known key so already-issued licenses keep verifying.
     */
    public void updateSyncedPublicKey(PublicKey key) {
        if (java.util.Objects.equals(this.syncedPublicKey, key)) {
            return;
        }
        this.syncedPublicKey = key;
        if (key == null) {
            log.info("[License] cleared console-synced public key");
        } else {
            log.info("[License] activated console-synced public key (overrides baked key)");
        }
    }

    /** The key verification runs against: console-synced first, then the baked fallback. */
    private PublicKey resolvePublicKey() {
        PublicKey synced = syncedPublicKey;
        if (synced != null) {
            return synced;
        }
        return bakedPublicKey;
    }

    /**
     * Which public key {@link #decodeAndVerify} currently runs against:
     * {@code synced} (console key pushed by the syncer), {@code baked} (the
     * in‑JAR offline fallback), or {@code none} (trial‑only). Exposed on the
     * license status endpoint so support can tell why a token is (or isn't)
     * verifying on a given till.
     */
    public String activeKeySource() {
        if (syncedPublicKey != null) {
            return "synced";
        }
        if (bakedPublicKey != null) {
            return "baked";
        }
        return "none";
    }

    private static Path resolveInitializedFile() {
        return Path.of(
            System.getenv().getOrDefault(
                "APP_DATA",
                System.getProperty("user.home") + "/.palmart"
            ),
            ".initialized"
        );
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
        PublicKey key = resolvePublicKey();
        if (key == null) {
            log.warn("[License] cannot verify — no public key configured (baked or synced)");
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
            sig.initVerify(key);
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
        // The till always exposes its Machine ID — the vendor needs it to issue
        // a bound license (Settings → License shows it with a copy button).
        String machineId = machineFingerprintProvider != null
            ? machineFingerprintProvider.get()
            : null;

        // ── Licensed mode ──────────────────────────────────────────────
        if (token != null && !token.isBlank()) {
            LicensePayload payload = decodeAndVerify(token);
            if (payload == null) {
                return LicenseStatus.invalid(
                    "The license signature is invalid or the token is corrupt."
                ).withMachineId(machineId);
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
                ).withMachineId(machineId);
            }

            // Machine binding: a license is valid only on the till it was
            // issued for. A token without a fingerprint is refused — this is
            // what stops one person's key working on another person's machine.
            String expected = payload.machineFingerprint();
            if (expected == null || expected.isBlank()) {
                return LicenseStatus.invalid(
                    "This license is not bound to a machine. Ask your vendor to " +
                    "re-issue it using the Machine ID shown below."
                ).withMachineId(machineId);
            }
            if (machineId == null || !expected.equalsIgnoreCase(machineId)) {
                return LicenseStatus.invalid(
                    "This license is bound to a different machine. Send the " +
                    "Machine ID shown below to your vendor to get a key for this computer."
                ).withMachineId(machineId);
            }

            if (
                payload.expiresAt() != null &&
                payload.expiresAt().isBefore(Instant.now())
            ) {
                return LicenseStatus.expired(
                    payload.plan(),
                    payload.expiresAt()
                ).withMachineId(machineId);
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
            ).withMachineId(machineId);
        }

        // ── Trial mode ────────────────────────────────────────────────
        if (!Files.exists(initializedFile)) {
            // Setup hasn't happened yet — no trial to check.
            return LicenseStatus.trialActive(TRIAL_DAYS).withMachineId(machineId);
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
                return LicenseStatus.trialActive(TRIAL_DAYS).withMachineId(machineId);
            }

            long daysSinceSetup = ChronoUnit.DAYS.between(
                setupAt,
                Instant.now()
            );
            long daysRemaining = TRIAL_DAYS - daysSinceSetup;

            if (daysRemaining <= 0) {
                return LicenseStatus.trialExpired(
                    setupAt.plus(TRIAL_DAYS, ChronoUnit.DAYS)
                ).withMachineId(machineId);
            }

            return LicenseStatus.trialActive(daysRemaining).withMachineId(machineId);
        } catch (IOException e) {
            log.warn(
                "[License] could not read .initialized file: {}",
                e.getMessage()
            );
            return LicenseStatus.trialActive(TRIAL_DAYS).withMachineId(machineId);
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

    /**
     * Derives the base64 X.509 public key matching a PKCS#8 Ed25519 private key.
     * Used by the platform's public-key endpoint so a till always learns the key
     * the vendor is actually signing with — even when only the private key was
     * configured (env var, or a console row saved without a public key).
     */
    public static String derivePublicKeyFromPrivate(String privateKeyBase64) {
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(privateKeyBase64.trim());
            var privateKey = (Ed25519PrivateKeyParameters) PrivateKeyFactory.createKey(pkcs8);
            byte[] rawPublic = privateKey.generatePublicKey().getEncoded();
            byte[] spki = new byte[ED25519_SPKI_PREFIX.length + rawPublic.length];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(rawPublic, 0, spki, ED25519_SPKI_PREFIX.length, rawPublic.length);
            String publicKeyBase64 = Base64.getEncoder().encodeToString(spki);
            decodePublicKey(publicKeyBase64); // sanity: must be a parseable Ed25519 X.509 key
            return publicKeyBase64;
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive Ed25519 public key from private key", e);
        }
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
