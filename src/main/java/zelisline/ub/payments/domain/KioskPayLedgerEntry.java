package zelisline.ub.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "kiosk_pay_ledger_entries")
public class KioskPayLedgerEntry {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "entry_type", nullable = false, length = 32)
    private String entryType;

    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "available_delta", nullable = false, precision = 14, scale = 2)
    private BigDecimal availableDelta = BigDecimal.ZERO;

    @Column(name = "pending_delta", nullable = false, precision = 14, scale = 2)
    private BigDecimal pendingDelta = BigDecimal.ZERO;

    @Column(name = "balance_after_available", nullable = false, precision = 14, scale = 2)
    private BigDecimal balanceAfterAvailable;

    @Column(name = "balance_after_pending", nullable = false, precision = 14, scale = 2)
    private BigDecimal balanceAfterPending;

    @Column(name = "reference", length = 128)
    private String reference;

    @Column(name = "context_type", length = 32)
    private String contextType;

    @Column(name = "context_id", length = 36)
    private String contextId;

    @Column(name = "withdrawal_id", length = 36)
    private String withdrawalId;

    @Column(name = "gateway_checkout_id", length = 36)
    private String gatewayCheckoutId;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
