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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Auto-settle after platform STK when the shop uses {@link GatewayType#CUSTODY_MPESA}.
 * {@link #provider} is the rail that collected — settle must use the same entity.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_custody_settlements")
public class PlatformCustodySettlement {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "gateway_config_id", nullable = false, length = 36)
    private String gatewayConfigId;

    @Column(name = "stk_push_id", nullable = false, length = 36)
    private String stkPushId;

    /** {@link PlatformMpesaCustodyProviders#KOPOKOPO} or {@link PlatformMpesaCustodyProviders#DARAJA}. */
    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "destination_type", nullable = false, length = 16)
    private String destinationType;

    @Column(name = "destination_till", length = 32)
    private String destinationTill;

    @Column(name = "destination_paybill", length = 32)
    private String destinationPaybill;

    @Column(name = "destination_account", length = 64)
    private String destinationAccount;

    @Column(name = "status", nullable = false, length = 24)
    private String status = PlatformCustodySettlementStatuses.PENDING;

    /** KopoKopo Send Money id or Daraja ConversationID. */
    @Column(name = "disbursement_id", length = 128)
    private String disbursementId;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null || status.isBlank()) {
            status = PlatformCustodySettlementStatuses.PENDING;
        }
        if (currency == null || currency.isBlank()) {
            currency = "KES";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
