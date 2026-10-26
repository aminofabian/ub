package zelisline.ub.domainoffer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A shop's offer row: audience membership, the public pay token, M-Pesa state,
 * and the ops fulfilment trail. One row per shop per campaign run.
 */
@Getter
@Setter
@Entity
@Table(name = "domain_offer_orders")
public class DomainOfferOrder {

    public static final long DEFAULT_PRICE_CENTS = 100_000L;

    public static final String REGISTRATION_NOT_NEEDED = "NOT_NEEDED";
    public static final String REGISTRATION_PENDING = "PENDING";
    public static final String REGISTRATION_ORDERED = "ORDERED";
    public static final String REGISTRATION_LIVE = "LIVE";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "campaign_id", nullable = false, length = 36)
    private String campaignId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** Short human reference (also used in the M-Pesa account reference). */
    @Column(name = "reference", nullable = false, length = 24)
    private String reference;

    /** Secret in the emailed / in-app pay URL. Long, random, URL-safe. */
    @Column(name = "pay_token", nullable = false, length = 64)
    private String payToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DomainOfferStatus status = DomainOfferStatus.INVITED;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain_mode", nullable = false, length = 24)
    private DomainOfferDomainMode domainMode = DomainOfferDomainMode.OWNED;

    @Column(name = "requested_domain", length = 255)
    private String requestedDomain;

    @Column(name = "suggested_domain", length = 255)
    private String suggestedDomain;

    @Column(name = "theme_key", length = 64)
    private String themeKey;

    @Column(name = "theme_direction", length = 32)
    private String themeDirection;

    @Lob
    @Column(name = "theme_notes", columnDefinition = "TEXT")
    private String themeNotes;

    @Column(name = "contact_name", length = 191)
    private String contactName;

    @Column(name = "contact_email", length = 191)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents = DEFAULT_PRICE_CENTS;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "touch_count", nullable = false)
    private int touchCount;

    @Column(name = "last_touch_at")
    private Instant lastTouchAt;

    @Column(name = "next_touch_at")
    private Instant nextTouchAt;

    @Column(name = "last_touch_kind", length = 24)
    private String lastTouchKind;

    @Column(name = "email_sent_count", nullable = false)
    private int emailSentCount;

    @Column(name = "in_app_sent_count", nullable = false)
    private int inAppSentCount;

    @Column(name = "last_email_error", length = 500)
    private String lastEmailError;

    @Column(name = "stk_push_id", length = 36)
    private String stkPushId;

    @Column(name = "payment_checkout_id", length = 128)
    private String paymentCheckoutId;

    @Column(name = "mpesa_receipt", length = 128)
    private String mpesaReceipt;

    @Column(name = "payer_phone", length = 32)
    private String payerPhone;

    @Column(name = "last_stk_status", length = 32)
    private String lastStkStatus;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "payment_started_at")
    private Instant paymentStartedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Paid at + 24 hours — the promise made in the campaign. */
    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Column(name = "assigned_to", length = 191)
    private String assignedTo;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "delivered_domain", length = 255)
    private String deliveredDomain;

    @Column(name = "domain_mapping_id", length = 36)
    private String domainMappingId;

    @Column(name = "domain_order_id", length = 36)
    private String domainOrderId;

    @Column(name = "registration_status", length = 24)
    private String registrationStatus;

    @Lob
    @Column(name = "fulfilment_notes", columnDefinition = "TEXT")
    private String fulfilmentNotes;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
        if (status == null) {
            status = DomainOfferStatus.INVITED;
        }
        if (domainMode == null) {
            domainMode = DomainOfferDomainMode.OWNED;
        }
        if (currency == null || currency.isBlank()) {
            currency = "KES";
        }
        if (amountCents <= 0) {
            amountCents = DEFAULT_PRICE_CENTS;
        }
        if (requestedDomain != null) {
            requestedDomain = requestedDomain.trim().toLowerCase();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (requestedDomain != null) {
            requestedDomain = requestedDomain.trim().toLowerCase();
        }
    }

    public String effectiveDomain() {
        if (requestedDomain != null && !requestedDomain.isBlank()) {
            return requestedDomain;
        }
        return suggestedDomain;
    }
}
