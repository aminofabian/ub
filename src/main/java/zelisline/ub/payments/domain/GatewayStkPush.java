package zelisline.ub.payments.domain;

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

@Getter
@Setter
@Entity
@Table(name = "gateway_stk_pushes")
public class GatewayStkPush {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_type", nullable = false, length = 32)
    private GatewayType gatewayType;

    @Column(name = "config_id", length = 36)
    private String configId;

    @Column(name = "gateway_checkout_id", nullable = false, length = 128)
    private String gatewayCheckoutId;

    @Column(name = "merchant_reference", nullable = false, length = 128)
    private String merchantReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "context_type", nullable = false, length = 32)
    private StkPushContextType contextType;

    @Column(name = "context_id", length = 36)
    private String contextId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "gateway_transaction_id", length = 128)
    private String gatewayTransactionId;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "poll_count", nullable = false)
    private int pollCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
            status = GatewayStkPushStatuses.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
