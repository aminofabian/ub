package zelisline.ub.restaurant.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Part (or all) of an order that is settled together: one bill becomes one ordinary sale, with
 * its own receipt, payments and tip. An order paid by three people has three bills (ADR 0020).
 */
@Getter
@Setter
@Entity
@Table(name = "order_bills")
public class OrderBill {

    public static final String OPEN = "OPEN";
    public static final String PAID = "PAID";
    /** On the house: closed with a reason and no sale. */
    public static final String COMP = "COMP";
    /** The customer left without paying: closed with a reason and no sale. */
    public static final String UNPAID = "UNPAID";
    /** Its sale was voided afterwards; its lines are unsettled again. */
    public static final String VOIDED = "VOIDED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    /** The order: a {@code pos_drafts} row of kind {@code ORDER}. */
    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    /** 1, 2, 3 within the order, for people to tell bills apart. */
    @Column(name = "bill_no", nullable = false)
    private int billNo;

    @Column(name = "status", nullable = false, length = 8)
    private String status = OPEN;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    /** Why a bill was closed without a sale. */
    @Column(name = "reason", length = 255)
    private String reason;

    /** What the bill added on top of its lines when it was paid; each is its own line on the sale. */
    @Column(name = "service_charge", nullable = false, precision = 14, scale = 2)
    private java.math.BigDecimal serviceCharge = java.math.BigDecimal.ZERO;

    @Column(name = "packaging_charge", nullable = false, precision = 14, scale = 2)
    private java.math.BigDecimal packagingCharge = java.math.BigDecimal.ZERO;

    @Column(name = "delivery_fee", nullable = false, precision = 14, scale = 2)
    private java.math.BigDecimal deliveryFee = java.math.BigDecimal.ZERO;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

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

    /** True while the bill still claims its lines: open, or closed in a way that settles them. */
    public boolean holdsItsLines() {
        return !VOIDED.equals(status);
    }

    public boolean isSettled() {
        return PAID.equals(status) || COMP.equals(status) || UNPAID.equals(status);
    }
}
