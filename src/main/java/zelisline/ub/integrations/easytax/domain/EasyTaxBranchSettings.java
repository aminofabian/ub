package zelisline.ub.integrations.easytax.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-branch EasyTax identity: the KRA branch id and the serial of the VSCU device
 * that reports for this branch.
 *
 * <p>These belong to the branch (a VSCU is approved per TIN + branch + serial), not to
 * the business-level integration settings — one serial per business would silently
 * assume a single device shop.
 */
@Getter
@Setter
@Entity
@Table(name = "easytax_branch_settings")
public class EasyTaxBranchSettings {

    @Id
    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "kra_branch_id", length = 32)
    private String kraBranchId;

    @Column(name = "device_serial", length = 64)
    private String deviceSerial;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isConfigured() {
        return isPresent(kraBranchId) && isPresent(deviceSerial);
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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
