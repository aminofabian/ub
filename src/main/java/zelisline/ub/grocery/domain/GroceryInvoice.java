package zelisline.ub.grocery.domain;

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
import zelisline.ub.grocery.GroceryConstants;

@Getter
@Setter
@Entity
@Table(name = "grocery_invoices")
public class GroceryInvoice {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "status", nullable = false, length = 24)
    private String status = GroceryConstants.STATUS_PENDING_PAYMENT;

    @Column(name = "barcode_code", nullable = false, length = 191)
    private String barcodeCode;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 500)
    private String cancelledReason;

    @Column(name = "paid_by", length = 36)
    private String paidBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    @Column(name = "locked_by", length = 36)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
        if (status == null || status.isBlank()) {
            status = GroceryConstants.STATUS_PENDING_PAYMENT;
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

    public boolean isPending() {
        return GroceryConstants.STATUS_PENDING_PAYMENT.equals(status);
    }

    public boolean isPaid() {
        return GroceryConstants.STATUS_PAID.equals(status);
    }

    public boolean isCancelled() {
        return GroceryConstants.STATUS_CANCELLED.equals(status);
    }

    public boolean isExpired() {
        return GroceryConstants.STATUS_EXPIRED.equals(status);
    }
}
