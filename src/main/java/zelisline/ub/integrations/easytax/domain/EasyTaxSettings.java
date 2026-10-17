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
 * Per-business EasyTax (KRA eTIMS) integration settings.
 *
 * <p>This row is the opt-in: {@link #enabled} is false until a shop chooses to
 * turn the integration on and supplies the device details. The password is
 * never stored in the clear — {@link #passwordEncrypted} holds the AES-256-GCM
 * blob produced by {@code CredentialEncryptionService}.
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

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    @Column(name = "username", length = 191)
    private String username;

    @Column(name = "password_encrypted", columnDefinition = "text")
    private String passwordEncrypted;

    @Column(name = "tin", length = 32)
    private String tin;

    @Column(name = "branch_id", length = 32)
    private String branchId;

    @Column(name = "device_serial", length = 64)
    private String deviceSerial;

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

    /** True when every field the API needs before it can talk to EasyTax is present. */
    public boolean isConfigured() {
        return isPresent(baseUrl)
                && isPresent(username)
                && isPresent(passwordEncrypted)
                && isPresent(tin)
                && isPresent(branchId)
                && isPresent(deviceSerial);
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
