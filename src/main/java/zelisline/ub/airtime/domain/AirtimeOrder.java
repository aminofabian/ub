package zelisline.ub.airtime.domain;

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

/** One airtime top-up sold by a tenant to a subscriber. */
@Getter
@Setter
@Entity
@Table(name = "airtime_orders")
public class AirtimeOrder {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    /** Cash, M-Pesa, or tab — how the shopper reimbursed the shop. */
    @Column(name = "tender", nullable = false, length = 16)
    private String tender = AirtimeTenders.CASH;

    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    /** M-Pesa number that paid, when different from {@link #phoneNumber}. */
    @Column(name = "payer_phone", length = 32)
    private String payerPhone;

    @Column(name = "network", length = 16)
    private String network;

    /** Face value delivered to the subscriber. */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** Wallet debit — held at request, settled on success. */
    @Column(name = "cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal cost;

    /** Tenant margin credited back on success. */
    @Column(name = "commission", nullable = false, precision = 14, scale = 2)
    private BigDecimal commission = BigDecimal.ZERO;

    @Column(name = "commission_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal commissionPercent = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "status", nullable = false, length = 16)
    private String status = AirtimeOrderStatuses.REQUESTED;

    /** Our reference sent to the provider; also the ledger reference stem. */
    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "provider_transaction_id", length = 128)
    private String providerTransactionId;

    @Column(name = "provider_status", length = 32)
    private String providerStatus;

    @Column(name = "provider_details", length = 255)
    private String providerDetails;

    @Column(name = "provider_discount", precision = 14, scale = 2)
    private BigDecimal providerDiscount;

    @Column(name = "provider_balance", precision = 14, scale = 2)
    private BigDecimal providerBalance;

    @Column(name = "receipt", length = 64)
    private String receipt;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    @Column(name = "web_order_id", length = 36)
    private String webOrderId;

    @Column(name = "customer_id", length = 36)
    private String customerId;

    @Column(name = "cashier_user_id", length = 36)
    private String cashierUserId;

    /** Storefront orders only dispatch once the shopper's payment is captured. */
    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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
        if (requestedAt == null) {
            requestedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return AirtimeOrderStatuses.isTerminal(status);
    }
}
