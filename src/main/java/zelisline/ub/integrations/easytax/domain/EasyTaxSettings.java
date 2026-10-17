package zelisline.ub.integrations.easytax.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-business EasyTax (KRA eTIMS) opt-in and credentials.
 *
 * <p>The EasyTax endpoint is platform-owned (see {@link PlatformEasyTaxSettings}); the
 * tenant only chooses an environment. KRA branch id and device serial live per branch
 * in {@link EasyTaxBranchSettings}. The password is never stored in the clear —
 * {@link #passwordEncrypted} holds the AES-256-GCM blob.
 */
@Getter
@Setter
@Entity
@Table(name = "easytax_settings")
public class EasyTaxSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 16)
    private EasyTaxEnvironment environment = EasyTaxEnvironment.SANDBOX;

    @Column(name = "username", length = 191)
    private String username;

    @Column(name = "password_encrypted", columnDefinition = "text")
    private String passwordEncrypted;

    @Column(name = "tin", length = 32)
    private String tin;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_message", length = 500)
    private String lastTestMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True when this tenant has supplied everything it owns before it can run. */
    public boolean isConfigured() {
        return isPresent(username) && isPresent(passwordEncrypted) && isPresent(tin);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (environment == null) {
            environment = EasyTaxEnvironment.SANDBOX;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static EasyTaxSettings disabledFor(String businessId) {
        EasyTaxSettings row = new EasyTaxSettings();
        row.setBusinessId(businessId);
        row.setEnabled(false);
        row.setEnvironment(EasyTaxEnvironment.SANDBOX);
        return row;
    }
}
