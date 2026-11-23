package zelisline.ub.wholesale.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/** What a buyer owes a supplier's shop for one dispatched trade order (ADR 0042). */
@Getter
@Setter
@Entity
@Table(name = "sales_invoices")
public class SalesInvoice {

    public static final String OPEN = "open";
    public static final String PART_PAID = "part_paid";
    public static final String PAID = "paid";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "sales_order_id", nullable = false, length = 36, unique = true)
    private String salesOrderId;

    @Column(name = "invoice_no", nullable = false, length = 32)
    private String invoiceNo;

    @Column(name = "status", nullable = false, length = 16)
    private String status = OPEN;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    /** Taken off what is owed by credit notes. */
    @Column(name = "amount_credited", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountCredited = BigDecimal.ZERO;

    /** What is still owed: the total, less what was received, less what was credited. */
    public BigDecimal owed() {
        return total.subtract(amountPaid).subtract(amountCredited == null ? BigDecimal.ZERO : amountCredited);
    }

    /** What the goods cost the shop, from the batches they were taken from. */
    @Column(name = "cost_of_goods", nullable = false, precision = 14, scale = 2)
    private BigDecimal costOfGoods = BigDecimal.ZERO;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "buyer_name", nullable = false, length = 255)
    private String buyerName;

    @Column(name = "buyer_business_name", length = 255)
    private String buyerBusinessName;

    @Column(name = "buyer_phone", nullable = false, length = 64)
    private String buyerPhone;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Desktop till only: when this row was mirrored from the cloud, so a mirror is never pushed back. */
    @Column(name = "cloud_synced_at")
    private Instant cloudSyncedAt;

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
