package zelisline.ub.billing.domain;

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
@Table(name = "platform_subscription_billing_settings")
public class PlatformSubscriptionBillingSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000003";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = SINGLETON_ID;

    @Column(name = "billing_enabled", nullable = false)
    private boolean billingEnabled;

    @Column(name = "default_grace_days", nullable = false)
    private int defaultGraceDays = 15;

    @Column(name = "renewal_base_url", nullable = false, length = 512)
    private String renewalBaseUrl = "https://palmart.co.ke/business/billing/renew";

    @Column(name = "notification_cadence_days", nullable = false, length = 128)
    private String notificationCadenceDays = "0,2,5,8,11,13,14,15";

    @Column(name = "pre_expiry_reminder_days", nullable = false)
    private int preExpiryReminderDays = 7;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
