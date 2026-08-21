package zelisline.ub.desktop.license;

import java.security.PrivateKey;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Vendor-side license issuance for Kiosk Desktop installs (Super Admin → Platform →
 * Desktop licenses). Signs tokens with the same Ed25519 private key the CLI tool
 * uses, so cloud-issued tokens verify on the till exactly like CLI-issued ones.
 *
 * <p>The private key is supplied via {@code APP_DESKTOP_LICENSE_PRIVATE_KEY} on
 * the cloud deployment (base64 PKCS#8, printed by
 * {@code backend/scripts/generate-license.sh keys}). It never ships with the
 * desktop app — the till only holds the matching public key
 * ({@code app.desktop.license.public-key}).
 */
@Service
public class DesktopLicenseIssuer {

    private static final Logger log = LoggerFactory.getLogger(DesktopLicenseIssuer.class);

    private final PrivateKey privateKey;

    public DesktopLicenseIssuer(
            @Value("${app.desktop.license.private-key:}") String privateKeyBase64) {
        if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
            log.warn(
                "[license-issuer] no private key configured — issuance disabled. "
                    + "Set APP_DESKTOP_LICENSE_PRIVATE_KEY (see backend/scripts/generate-license.sh keys)."
            );
            this.privateKey = null;
        } else {
            this.privateKey = LicenseService.decodePrivateKey(privateKeyBase64);
            log.info("[license-issuer] Ed25519 private key loaded — issuance enabled");
        }
    }

    /** Whether the issuer can sign tokens (private key present in env). */
    public boolean isConfigured() {
        return privateKey != null;
    }

    /** Signs a license token for the given shop. Throws 503 when not configured. */
    public IssuedLicense issue(String businessName, String plan, Instant expiresAt, String fingerprint) {
        if (privateKey == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The license issuer is not configured on this deployment. "
                    + "Set APP_DESKTOP_LICENSE_PRIVATE_KEY and restart."
            );
        }
        LicensePayload payload = new LicensePayload(businessName, plan, Instant.now(), expiresAt, fingerprint);
        String token = LicenseService.encodeToken(payload, privateKey);
        log.info(
            "[license-issuer] issued {} plan for '{}' (expires={})",
            plan, businessName, expiresAt == null ? "perpetual" : expiresAt
        );
        return new IssuedLicense(token, payload);
    }

    public record IssuedLicense(String token, LicensePayload payload) {}
}
