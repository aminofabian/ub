package zelisline.ub.opsalerts.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "business_ops_alert_settings")
public class BusinessOpsAlertSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "alert_web_order", nullable = false)
    private boolean alertWebOrder = true;

    @Column(name = "alert_shift", nullable = false)
    private boolean alertShift = true;

    @Column(name = "alert_supply", nullable = false)
    private boolean alertSupply = true;

    @Column(name = "alert_credit_payment", nullable = false)
    private boolean alertCreditPayment = true;

    @Column(name = "alert_restock_digest", nullable = false)
    private boolean alertRestockDigest = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public boolean hasVerifiedPhone() {
        return phone != null && !phone.isBlank() && phoneVerifiedAt != null;
    }
}
