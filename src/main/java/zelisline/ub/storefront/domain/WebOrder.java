package zelisline.ub.storefront.domain;

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
import zelisline.ub.storefront.WebOrderCodes;

@Getter
@Setter
@Entity
@Table(name = "web_orders")
public class WebOrder {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "cart_id", nullable = false, length = 36)
    private String cartId;

    @Column(name = "catalog_branch_id", nullable = false, length = 36)
    private String catalogBranchId;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "fulfillment_status", length = 24)
    private String fulfillmentStatus;

    @Column(name = "payment_checkout_id", length = 128)
    private String paymentCheckoutId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "customer_name", nullable = false, length = 255)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 64)
    private String customerPhone;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "notes", length = 2000)
    private String notes;

    /** WEB | WHATSAPP | POS — first-class channel (Phase 3). */
    @Column(name = "channel", nullable = false, length = 24)
    private String channel = "WEB";

    /** Canonical short order code quoted in chat (scope D11). */
    @Column(name = "code", length = 24)
    private String code;

    /** opened | reopened | expired — what we can actually observe about the handoff. */
    @Column(name = "handoff_state", length = 24)
    private String handoffState;

    @Column(name = "handoff_opened_at")
    private Instant handoffOpenedAt;

    /** When an unconfirmed WhatsApp order releases its stock (scope §11). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Set when a cashier till has claimed/auto-printed the pickup ticket (once). */
    @Column(name = "pickup_ticket_printed_at")
    private Instant pickupTicketPrintedAt;

    /** SHA-256 hash of the one-tap receipt token (Phase 5); the raw token is never stored. */
    @Column(name = "receipt_token_hash", length = 64)
    private String receiptTokenHash;

    @Column(name = "receipt_token_expires_at")
    private Instant receiptTokenExpiresAt;

    /** Set once the token is redeemed — single-use. */
    @Column(name = "receipt_token_consumed_at")
    private Instant receiptTokenConsumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** When this order was synced with the cloud (null = pending push). */
    @Column(name = "cloud_synced_at")
    private Instant cloudSyncedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant n = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (code == null || code.isBlank()) {
            code = WebOrderCodes.code(id);
        }
        if (channel == null || channel.isBlank()) {
            channel = "WEB";
        }
        createdAt = n;
        updatedAt = n;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
