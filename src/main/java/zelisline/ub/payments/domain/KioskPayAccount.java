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

@Getter
@Setter
@Entity
@Table(name = "kiosk_pay_accounts")
public class KioskPayAccount {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = KioskPayAccountStatuses.OFF;

    @Column(name = "payout_phone", length = 32)
    private String payoutPhone;

    @Column(name = "available_balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Column(name = "pending_balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal pendingBalance = BigDecimal.ZERO;

    @Column(name = "lifetime_in", nullable = false, precision = 14, scale = 2)
    private BigDecimal lifetimeIn = BigDecimal.ZERO;

    @Column(name = "lifetime_out", nullable = false, precision = 14, scale = 2)
    private BigDecimal lifetimeOut = BigDecimal.ZERO;

    @Column(name = "fee_percent_override", precision = 6, scale = 3)
    private BigDecimal feePercentOverride;

    @Column(name = "storefront_enabled", nullable = false)
    private boolean storefrontEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isActive() {
        return KioskPayAccountStatuses.ACTIVE.equals(status);
    }
}
