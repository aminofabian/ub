package zelisline.ub.airtime.domain;

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

/**
 * Singleton platform settings for airtime resale. The platform owns one
 * Instalipa application (and therefore one float); every tenant draws that
 * float down against their own Kiosk Pay wallet balance.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_airtime_settings")
public class PlatformAirtimeSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "provider", nullable = false, length = 24)
    private String provider = "INSTALIPA";

    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl = "https://business.instalipa.co.ke";

    @Column(name = "environment", nullable = false, length = 16)
    private String environment = "sandbox";

    @Column(name = "credentials_enc", columnDefinition = "TEXT")
    private String credentialsEnc;

    @Column(name = "tenant_commission_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal tenantCommissionPercent = new BigDecimal("3.000");

    @Column(name = "min_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal minAmount = new BigDecimal("5.00");

    @Column(name = "max_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal maxAmount = new BigDecimal("5000.00");

    @Column(name = "daily_tenant_limit", nullable = false, precision = 14, scale = 2)
    private BigDecimal dailyTenantLimit = new BigDecimal("50000.00");

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "pos_enabled", nullable = false)
    private boolean posEnabled = true;

    @Column(name = "storefront_enabled", nullable = false)
    private boolean storefrontEnabled = true;

    /** Last float figure Instalipa reported on any response. */
    @Column(name = "float_balance", precision = 14, scale = 2)
    private BigDecimal floatBalance;

    @Column(name = "float_low_threshold", nullable = false, precision = 14, scale = 2)
    private BigDecimal floatLowThreshold = new BigDecimal("5000.00");

    @Column(name = "float_checked_at")
    private Instant floatCheckedAt;

    /** While set (future timestamp), sends fail fast — the platform float is dry. */
    @Column(name = "float_constrained_until")
    private Instant floatConstrainedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null || id.isBlank()) {
            id = SINGLETON_ID;
        }
        updatedAt = Instant.now();
    }

    public boolean isFloatConstrained(Instant now) {
        return floatConstrainedUntil != null && floatConstrainedUntil.isAfter(now);
    }

    public boolean isFloatLow() {
        return floatBalance != null && floatBalance.compareTo(floatLowThreshold) < 0;
    }
}
