package zelisline.ub.marketplace.domain;

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
 * Supplier-bound escrow obligation funded from the shop's Kiosk Pay wallet.
 * Float remains in {@code kiosk_pay_*}; this row is the hold/release state machine.
 */
@Getter
@Setter
@Entity
@Table(name = "marketplace_escrow_holds")
public class MarketplaceEscrowHold {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "supplier_id", nullable = false, length = 36)
    private String supplierId;

    @Column(name = "marketplace_supplier_id", length = 36)
    private String marketplaceSupplierId;

    @Column(name = "purchase_order_id", length = 36)
    private String purchaseOrderId;

    @Column(name = "supplier_invoice_id", length = 36)
    private String supplierInvoiceId;

    @Column(name = "kiosk_pay_account_id", nullable = false, length = 36)
    private String kioskPayAccountId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "status", nullable = false, length = 24)
    private String status = MarketplaceEscrowHoldStatuses.HELD;

    @Column(name = "release_trigger", length = 24)
    private String releaseTrigger;

    @Column(name = "funded_ledger_reference", length = 128)
    private String fundedLedgerReference;

    @Column(name = "settle_disbursement_id", length = 36)
    private String settleDisbursementId;

    @Column(name = "kopokopo_send_money_id", length = 128)
    private String kopokopoSendMoneyId;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "note", length = 512)
    private String note;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

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
}
