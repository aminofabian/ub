package zelisline.ub.desktop.license;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint the desktop till polls to learn the public key the vendor is
 * currently signing licenses with (see {@link DesktopLicenseKeySyncer}).
 *
 * <p>A public key needs no authentication — the private key never leaves the
 * platform. The key is derived from whichever signing key {@link
 * DesktopLicenseIssuer} resolves (deployment env var first, else the
 * console-managed key), so the till always learns the exact key tokens are
 * being signed with, even after a rotation or when only the private key was
 * configured. This removes the need to re-bake + re-release the desktop app on
 * every key change.
 */
@RestController
@RequestMapping("/api/v1/platform/desktop-license-public-key")
@RequiredArgsConstructor
public class PlatformDesktopLicensePublicKeyController {

    private static final Logger log = LoggerFactory.getLogger(
        PlatformDesktopLicensePublicKeyController.class
    );

    private final DesktopLicenseIssuer issuer;
    private final DesktopLicenseIssuerConfigRepository configRepository;

    @GetMapping
    public PublicKeyResponse publicKey() {
        DesktopLicenseIssuerConfig row = configRepository
            .findById(DesktopLicenseIssuerConfig.SINGLETON_ID)
            .orElse(null);

        String signingKey = issuer.resolvePrivateKeyBase64();
        if (signingKey == null) {
            return new PublicKeyResponse(
                null,
                "none",
                row == null ? null : row.getUpdatedAt()
            );
        }

        String publicKey = null;
        try {
            publicKey = LicenseService.derivePublicKeyFromPrivate(signingKey);
        } catch (RuntimeException e) {
            log.warn(
                "[license-issuer] could not derive public key from signing key: {}",
                e.getMessage()
            );
        }
        if (
            publicKey == null &&
            row != null &&
            row.getPublicKey() != null &&
            !row.getPublicKey().isBlank()
        ) {
            publicKey = row.getPublicKey().trim();
        }
        return new PublicKeyResponse(
            publicKey,
            issuer.hasEnvKey() ? "env" : "console",
            row == null ? null : row.getUpdatedAt()
        );
    }

    /**
     * @param publicKey base64 X.509 Ed25519 public key, or null when the
     *                  platform has no signing key configured
     * @param source    {@code env}, {@code console}, or {@code none} — mirrors
     *                  the issuer status shown in the Super Admin console
     */
    public record PublicKeyResponse(
        String publicKey,
        String source,
        Instant updatedAt
    ) {}
}
