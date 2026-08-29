package zelisline.ub.messaging.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Platform-wide SMS credit configuration (singleton row, edited in Super Admin).
 * Mirrors the {@code platform_integration_settings} singleton pattern.
 */
@Entity
@Table(name = "platform_sms_credit_settings")
@Getter
@Setter
public class PlatformSmsCreditSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000002";

    public static final BigDecimal DEFAULT_UNIT_PRICE_KES = new BigDecimal("1.00");
    public static final int DEFAULT_MIN_PURCHASE = 10;
    public static final int DEFAULT_MAX_PURCHASE = 500;
    public static final int DEFAULT_LOW_BALANCE_THRESHOLD = 5;
    public static final String DEFAULT_CYCLE_TIMEZONE = "Africa/Nairobi";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "unit_price_kes", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceKes = DEFAULT_UNIT_PRICE_KES;

    @Column(name = "min_purchase_credits", nullable = false)
    private int minPurchaseCredits = DEFAULT_MIN_PURCHASE;

    @Column(name = "max_purchase_credits", nullable = false)
    private int maxPurchaseCredits = DEFAULT_MAX_PURCHASE;

    @Column(name = "low_balance_threshold", nullable = false)
    private int lowBalanceThreshold = DEFAULT_LOW_BALANCE_THRESHOLD;

    @Column(name = "cycle_timezone", nullable = false, length = 64)
    private String cycleTimezone = DEFAULT_CYCLE_TIMEZONE;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
