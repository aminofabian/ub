package zelisline.ub.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Singleton platform settings for Kiosk Pay (custody product).
 */
@Getter
@Setter
@Entity
@Table(name = "platform_kiosk_pay_settings")
public class PlatformKioskPaySettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000002";

    /** Synthetic config id used on {@code gateway_checkouts} for platform Paystack. */
    public static final String PLATFORM_PAYSTACK_CONFIG_ID = "platform-kiosk-pay-paystack";

    /** Synthetic config id for platform KopoKopo (withdraw / future STK). */
    public static final String PLATFORM_KOPOKOPO_CONFIG_ID = "platform-kiosk-pay-kopokopo";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "fee_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal feePercent = new BigDecimal("1.000");

    @Column(name = "min_withdraw_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal minWithdrawAmount = new BigDecimal("100.00");

    @Column(name = "daily_withdraw_limit", nullable = false, precision = 14, scale = 2)
    private BigDecimal dailyWithdrawLimit = new BigDecimal("200000.00");

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "paystack_environment", nullable = false, length = 16)
    private String paystackEnvironment = "sandbox";

    @Column(name = "paystack_credentials_enc", columnDefinition = "TEXT")
    private String paystackCredentialsEnc;

    @Column(name = "kopokopo_environment", nullable = false, length = 16)
    private String kopokopoEnvironment = "sandbox";

    @Column(name = "kopokopo_credentials_enc", columnDefinition = "TEXT")
    private String kopokopoCredentialsEnc;

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
}
