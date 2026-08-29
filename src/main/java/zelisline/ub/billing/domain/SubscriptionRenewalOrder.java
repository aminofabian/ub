package zelisline.ub.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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

@Entity
@Table(name = "subscription_renewal_orders")
@Getter
@Setter
public class SubscriptionRenewalOrder {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "tier_code", nullable = false, length = 64)
    private String tierCode;

    @Column(name = "period_months", nullable = false)
    private int periodMonths = 1;

    @Column(name = "amount_kes", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountKes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SubscriptionRenewalOrderStatus status;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "stk_push_id", length = 36)
    private String stkPushId;

    @Column(name = "mpesa_receipt", length = 64)
    private String mpesaReceipt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = SubscriptionRenewalOrderStatus.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
