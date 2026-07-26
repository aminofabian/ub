package zelisline.ub.marketplace.domain;

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
@Table(name = "supplier_portal_notification_prefs")
public class SupplierPortalNotificationPref {

    @Id
    @Column(name = "supplier_user_id", nullable = false, length = 36)
    private String supplierUserId;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "notify_po_in_app", nullable = false)
    private boolean notifyPoInApp = true;

    @Column(name = "notify_po_sms", nullable = false)
    private boolean notifyPoSms = true;

    @Column(name = "notify_payment_in_app", nullable = false)
    private boolean notifyPaymentInApp = true;

    @Column(name = "notify_payment_sms", nullable = false)
    private boolean notifyPaymentSms = true;

    @Column(name = "notify_delivery_in_app", nullable = false)
    private boolean notifyDeliveryInApp = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
