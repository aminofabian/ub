package zelisline.ub.desktop.license;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Console-managed desktop license signing key (Super Admin → Platform →
 * Desktop licenses → "License issuer key").
 *
 * <p>Single-row table. The private key is stored encrypted at rest via
 * {@code CredentialEncryptionService} (AES-256-GCM), so a super admin can
 * configure license issuance from the console without touching the deployment
 * environment. The plaintext base64 public key is kept alongside purely so the
 * console can show operators the exact value that must be baked into the
 * desktop JAR ({@code app.desktop.license.public-key}).
 *
 * <p>{@code APP_DESKTOP_LICENSE_PRIVATE_KEY} on the deployment takes precedence
 * over this row whenever it is set.
 */
@Entity
@Table(name = "desktop_license_issuer_config")
@Getter
@Setter
public class DesktopLicenseIssuerConfig {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Encrypted base64 PKCS#8 Ed25519 private key (AES-256-GCM blob). */
    @Column(name = "private_key_enc", columnDefinition = "TEXT", nullable = false)
    private String privateKeyEnc;

    /** Base64 X.509 Ed25519 public key — NOT secret; shown for JAR pairing checks. */
    @Column(name = "public_key", length = 256)
    private String publicKey;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
