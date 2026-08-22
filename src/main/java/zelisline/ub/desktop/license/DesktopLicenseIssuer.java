package zelisline.ub.desktop.license;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.payments.infrastructure.CredentialEncryptionService;

/**
 * Vendor-side license issuance for Kiosk Desktop installs (Super Admin → Platform →
 * Desktop licenses). Signs tokens with the same Ed25519 private key the CLI tool
 * uses, so cloud-issued tokens verify on the till exactly like CLI-issued ones.
 *
 * <p>The private key is resolved in priority order:
 * <ol>
 *   <li>{@code APP_DESKTOP_LICENSE_PRIVATE_KEY} on the deployment (base64 PKCS#8,
 *       printed by {@code backend/scripts/generate-license.sh keys}) — the classic
 *       deployment-environment path;</li>
 *   <li>the console-managed key in {@code desktop_license_issuer_config} (saved from
 *       the Super Admin console, encrypted at rest) — no restart needed.</li>
 * </ol>
 * It never ships with the desktop app — the till only holds the matching public key
 * ({@code app.desktop.license.public-key}).
 */
@Service
public class DesktopLicenseIssuer {

    private static final Logger log = LoggerFactory.getLogger(DesktopLicenseIssuer.class);

    private final String envPrivateKeyBase64;
    private final DesktopLicenseIssuerConfigRepository configRepository;
    private final CredentialEncryptionService encryptionService;

    @Autowired
    public DesktopLicenseIssuer(
            @Value("${app.desktop.license.private-key:}") String envPrivateKeyBase64,
            DesktopLicenseIssuerConfigRepository configRepository,
            CredentialEncryptionService encryptionService) {
        this.envPrivateKeyBase64 = blankToNull(envPrivateKeyBase64);
        this.configRepository = configRepository;
        this.encryptionService = encryptionService;
        if (this.envPrivateKeyBase64 == null) {
            log.warn(
                "[license-issuer] no private key in env (APP_DESKTOP_LICENSE_PRIVATE_KEY) "
                    + "— a console-managed key may still be configured."
            );
        } else {
            log.info("[license-issuer] Ed25519 private key loaded from env — issuance enabled");
        }
    }

    /**
     * Env-only issuer (tests / tooling). No database lookup is performed, so the
     * issuer is configured only when an env-style key is supplied.
     */
    public DesktopLicenseIssuer(String envPrivateKeyBase64) {
        this(envPrivateKeyBase64, null, null);
    }

    /** Whether the deployment env var is set (takes precedence over the console key). */
    public boolean hasEnvKey() {
        return envPrivateKeyBase64 != null;
    }

    /** Whether the issuer can sign tokens (env key, or console key present in the DB). */
    public boolean isConfigured() {
        return resolvePrivateKeyBase64() != null;
    }

    /**
     * Signs a license token for the given shop. Throws 503 when not configured
     * and 400 when the machine fingerprint is missing — every license must be
     * bound to a specific till so a key can't be used on another machine.
     */
    public IssuedLicense issue(String businessName, String plan, Instant expiresAt, String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A Machine ID is required — ask the shop owner for the Machine ID "
                    + "shown in Kiosk Desktop → Settings → License."
            );
        }
        String privateKeyBase64 = resolvePrivateKeyBase64();
        if (privateKeyBase64 == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The license issuer is not configured on this deployment. "
                    + "Configure the signing key from Super Admin → Platform → Desktop licenses."
            );
        }
        LicensePayload payload = new LicensePayload(businessName, plan, Instant.now(), expiresAt, fingerprint);
        String token = LicenseService.encodeToken(payload, LicenseService.decodePrivateKey(privateKeyBase64));
        log.info(
            "[license-issuer] issued {} plan for '{}' (expires={})",
            plan, businessName, expiresAt == null ? "perpetual" : expiresAt
        );
        return new IssuedLicense(token, payload);
    }

    /**
     * Resolves the signing key each call so a console-managed key change is
     * picked up immediately (no restart). The env var wins when present.
     */
    private String resolvePrivateKeyBase64() {
        if (envPrivateKeyBase64 != null) {
            return envPrivateKeyBase64;
        }
        if (configRepository == null || encryptionService == null) {
            return null;
        }
        return configRepository
            .findById(DesktopLicenseIssuerConfig.SINGLETON_ID)
            .map(DesktopLicenseIssuerConfig::getPrivateKeyEnc)
            .map(encryptionService::decrypt)
            .orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record IssuedLicense(String token, LicensePayload payload) {}
}
