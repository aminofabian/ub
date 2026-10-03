package zelisline.ub.platform.installs;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One Kiosk Desktop install that has checked in with the platform
 * (Super Admin → Platform → Desktop licenses → Installs).
 *
 * <p>The till upserts this by {@code install_id} on each check-in, carrying its
 * Machine ID, the shop name it was set up with, and (once connected) the cloud
 * business id. It exists so the vendor can issue a machine-bound activation key
 * without the shop owner having to find and send their Machine ID by hand — and
 * so a paid, connected shop can be activated automatically.
 */
@Entity
@Table(name = "desktop_installs")
@Getter
@Setter
public class DesktopInstall {

    /** Stable per-install id ({@code APP_DATA/conf/install-id} on the till). */
    @Id
    @Column(name = "install_id", length = 64)
    private String installId;

    /** The till's Machine ID (SHA-256 hex) that a license token is bound to. */
    @Column(name = "machine_fingerprint", nullable = false, length = 64)
    private String machineFingerprint;

    /** Cloud business this install is connected to, when it has signed in online. */
    @Column(name = "cloud_business_id", length = 36)
    private String cloudBusinessId;

    /** Shop name entered in the till's setup wizard (must match the license). */
    @Column(name = "business_name", nullable = false, length = 191)
    private String businessName;

    /** Owner email from setup / cloud — the default recipient for an activation key. */
    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "app_version", length = 64)
    private String appVersion;

    /** OS family reported by the till (e.g. {@code macos}, {@code windows}, {@code linux}). */
    @Column(name = "platform", length = 64)
    private String platform;

    /** Last license state the till reported: {@code active|trial|expired|...}. */
    @Column(name = "license_state", length = 32)
    private String licenseState;

    /** Last plan the till reported (mirrors the shop's cloud subscription tier). */
    @Column(name = "plan", length = 32)
    private String plan;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_ip", length = 64)
    private String lastIp;

    /** License issue most recently created for this install (manual or automatic). */
    @Column(name = "last_license_issue_id", length = 36)
    private String lastLicenseIssueId;

    /** Set when the platform issued an activation key automatically (paid shop). */
    @Column(name = "auto_issued_at")
    private Instant autoIssuedAt;
}
