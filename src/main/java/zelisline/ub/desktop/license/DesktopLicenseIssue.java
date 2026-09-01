package zelisline.ub.desktop.license;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One license token issued from the Super Admin console
 * (Super Admin → Platform → Desktop licenses).
 *
 * <p>The token is stored so "resend" can re-email the exact artifact that was
 * originally issued. Expired licenses are re-issued (a new row) rather than
 * edited.
 */
@Entity
@Table(name = "desktop_license_issues")
@Getter
@Setter
public class DesktopLicenseIssue {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Shop the license was issued to (must match the till's setup name). */
    @Column(name = "business_name", nullable = false, length = 255)
    private String businessName;

    /** Subscription tier mirroring the shop's cloud plan ({@code free|starter|business|growth|enterprise}). */
    @Column(name = "plan", nullable = false, length = 64)
    private String plan;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** Null = perpetual. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "machine_fingerprint", length = 255)
    private String machineFingerprint;

    /** Email the token was sent to, when delivered by the console. */
    @Column(name = "recipient_email", length = 255)
    private String recipientEmail;

    @Column(name = "email_sent", nullable = false)
    private boolean emailSent;

    /** The signed license token (base64url payload + signature). */
    @Column(name = "token", nullable = false, length = 2048)
    private String token;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
