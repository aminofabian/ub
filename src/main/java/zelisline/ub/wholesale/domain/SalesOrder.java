package zelisline.ub.wholesale.domain;

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
 * An order a trade buyer places with a supplier's shop (ADR 0040). It belongs to the supplier's
 * tenant. Placing it takes no stock and it never expires; the supplier answers it.
 */
@Getter
@Setter
@Entity
@Table(name = "sales_orders")
public class SalesOrder {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "status", nullable = false, length = 24)
    private String status = SalesOrderStatuses.PLACED;

    @Column(name = "source", nullable = false, length = 24)
    private String source = SalesOrderStatuses.SOURCE_STOREFRONT;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "buyer_name", nullable = false, length = 255)
    private String buyerName;

    @Column(name = "buyer_phone", nullable = false, length = 64)
    private String buyerPhone;

    @Column(name = "buyer_email", length = 255)
    private String buyerEmail;

    @Column(name = "buyer_business_name", length = 255)
    private String buyerBusinessName;

    @Column(name = "notes", length = 2000)
    private String notes;

    /** The buyer's phone number in one spelling, for matching. Null when it is not a usable number. */
    @Column(name = "buyer_phone_key", length = 32)
    private String buyerPhoneKey;

    /** {@code prepaid} unless the buyer has an approved account with this shop, then {@code credit}. */
    @Column(name = "payment_terms", nullable = false, length = 16)
    private String paymentTerms = SalesOrderStatuses.TERMS_PREPAID;

    @Column(name = "trade_account_id", length = 36)
    private String tradeAccountId;

    /**
     * The shop this order is for, when it was placed on the marketplace by a signed-in buyer who
     * is staff of that shop (ADR 0043). Null for an order typed in on the storefront.
     */
    @Column(name = "buyer_business_id", length = 36)
    private String buyerBusinessId;

    /** True when this order took the buyer's account over its credit limit. The supplier decides. */
    @Column(name = "over_credit_limit", nullable = false)
    private boolean overCreditLimit;

    /** When the supplier answered the order (accepted, part-accepted or rejected). Null before that. */
    @Column(name = "answered_at")
    private Instant answeredAt;

    /** When the accepted goods were dispatched. Null before that. */
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    /** The status an order was cancelled from ({@code placed} or an accepted status). Null unless cancelled. */
    @Column(name = "cancelled_from", length = 24)
    private String cancelledFrom;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Platform support removed this order from the supplier's standing. Reversible; the note is kept. */
    @Column(name = "standing_excluded", nullable = false)
    private boolean standingExcluded;

    @Column(name = "standing_excluded_at")
    private Instant standingExcludedAt;

    @Column(name = "standing_exclusion_note", length = 500)
    private String standingExclusionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
