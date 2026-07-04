package zelisline.ub.inventory.domain;

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
import zelisline.ub.inventory.InventoryConstants;

@Getter
@Setter
@Entity
@Table(name = "stock_take_restock_items")
public class StockTakeRestockItem {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "daily_audit_id", length = 36)
    private String dailyAuditId;

    @Column(name = "stock_take_session_id", nullable = false, length = 36)
    private String stockTakeSessionId;

    @Column(name = "stock_take_line_id", nullable = false, length = 36)
    private String stockTakeLineId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "supplier_id", nullable = false, length = 36)
    private String supplierId;

    @Column(name = "suggested_qty", nullable = false, precision = 14, scale = 4)
    private BigDecimal suggestedQty;

    @Column(name = "buying_price", precision = 14, scale = 4)
    private BigDecimal buyingPrice;

    @Column(name = "supplier_pack_size", precision = 14, scale = 4)
    private BigDecimal supplierPackSize;

    @Column(name = "supplier_pack_unit", length = 32)
    private String supplierPackUnit;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "status", nullable = false, length = 32)
    private String status = InventoryConstants.RESTOCK_STATUS_PENDING;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "added_by", nullable = false, length = 36)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "order_drafted_by", length = 36)
    private String orderDraftedBy;

    @Column(name = "order_drafted_at")
    private Instant orderDraftedAt;

    @Column(name = "purchase_order_id", length = 36)
    private String purchaseOrderId;

    @Column(name = "order_number", length = 64)
    private String orderNumber;

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
        if (createdAt == null) {
            createdAt = now;
        }
        if (addedAt == null) {
            addedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
