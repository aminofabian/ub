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
import zelisline.ub.grocery.GroceryDraftConstants;

@Getter
@Setter
@Entity
@Table(name = "grocery_drafts")
public class GroceryDraft {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "counter_number", nullable = false)
    private long counterNumber;

    @Column(name = "status", nullable = false, length = 16)
    private String status = GroceryDraftConstants.STATUS_BUILDING;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "invoice_id", length = 36)
    private String invoiceId;

    @Column(name = "issue_idempotency_key", length = 64)
    private String issueIdempotencyKey;

    @Column(name = "client_draft_id", length = 64)
    private String clientDraftId;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "sub_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal subTotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 500)
    private String cancelledReason;

    @Column(name = "issued_at")
    private Instant issuedAt;

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
            status = GroceryDraftConstants.STATUS_BUILDING;
        }
        if (subTotal == null) {
            subTotal = BigDecimal.ZERO;
        }
        if (discountTotal == null) {
            discountTotal = BigDecimal.ZERO;
        }
        if (taxTotal == null) {
            taxTotal = BigDecimal.ZERO;
        }
        if (grandTotal == null) {
            grandTotal = BigDecimal.ZERO;
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

    public boolean isBuilding() {
        return GroceryDraftConstants.STATUS_BUILDING.equals(status);
    }

    public boolean isIssued() {
        return GroceryDraftConstants.STATUS_ISSUED.equals(status);
    }

    public boolean isCancelled() {
        return GroceryDraftConstants.STATUS_CANCELLED.equals(status);
    }
}
