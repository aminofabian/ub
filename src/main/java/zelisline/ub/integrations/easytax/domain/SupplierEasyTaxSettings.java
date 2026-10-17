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
 * Supplier-side EasyTax opt-in, keyed by marketplace supplier id.
 *
 * <p>A supplier files its own sales invoices under its own TIN using its own
 * EasyTax/VSCU — that is a different taxpayer from the shop, so this is deliberately
 * separate from {@link EasyTaxSettings}. One site per supplier: the KRA branch id and
 * device serial live here rather than per shop.
 */
@Getter
@Setter
@Entity
@Table(name = "supplier_easytax_settings")
public class SupplierEasyTaxSettings {

    @Id
    @Column(name = "supplier_id", nullable = false, length = 36)
    private String supplierId;

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

    @Column(name = "kra_branch_id", length = 32)
    private String kraBranchId;

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

    /** Everything the supplier owns must be present before it may opt in. */
    public boolean isConfigured() {
        return isPresent(username)
                && isPresent(passwordEncrypted)
                && isPresent(tin)
                && isPresent(kraBranchId)
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

    public static SupplierEasyTaxSettings disabledFor(String supplierId) {
        SupplierEasyTaxSettings row = new SupplierEasyTaxSettings();
        row.setSupplierId(supplierId);
        row.setEnabled(false);
        row.setEnvironment(EasyTaxEnvironment.SANDBOX);
        return row;
    }
}
