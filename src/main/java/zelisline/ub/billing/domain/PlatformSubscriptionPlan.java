package zelisline.ub.billing.domain;

import java.math.BigDecimal;
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
@Table(name = "platform_subscription_plans")
public class PlatformSubscriptionPlan {

    @Id
    @Column(name = "tier_code", nullable = false, length = 64)
    private String tierCode;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "monthly_price_kes", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyPriceKes = BigDecimal.ZERO;

    @Column(name = "annual_price_kes", precision = 12, scale = 2)
    private BigDecimal annualPriceKes;

    @Column(name = "grace_days", nullable = false)
    private int graceDays = 15;

    @Column(name = "product_limit")
    private Integer productLimit;

    @Column(name = "cashier_limit")
    private Integer cashierLimit;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
