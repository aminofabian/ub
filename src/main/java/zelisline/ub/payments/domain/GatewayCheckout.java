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

/**
 * A provider-hosted checkout attempt (Paystack v1: initialize transaction →
 * authorization URL), persisted as the platform's record of the attempt.
 *
 * <p>{@link #reference} is globally unique and doubles as the webhook routing
 * key: a webhook is resolved to this row (and thereby to its tenant config)
 * before signature verification, because the Paystack HMAC uses the tenant's
 * secret key.
 */
@Getter
@Setter
@Entity
@Table(name = "gateway_checkouts")
public class GatewayCheckout {

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

    /** Unique; the webhook routing key (see 4.6 of the Paystack scope doc). */
    @Column(name = "reference", nullable = false, length = 128)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "context_type", nullable = false, length = 32)
    private GatewayCheckoutContextType contextType;

    @Column(name = "context_id", length = 36)
    private String contextId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "provider_transaction_id", length = 64)
    private String providerTransactionId;

    @Column(name = "access_code", length = 64)
    private String accessCode;

    @Column(name = "authorization_url", length = 512)
    private String authorizationUrl;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "verify_count", nullable = false)
    private int verifyCount;

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
            status = GatewayCheckoutStatuses.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
