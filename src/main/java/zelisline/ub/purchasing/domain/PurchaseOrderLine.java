package zelisline.ub.purchasing.domain;

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

@Getter
@Setter
@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "purchase_order_id", nullable = false, length = 36)
    private String purchaseOrderId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "qty_ordered", nullable = false, precision = 14, scale = 4)
    private BigDecimal qtyOrdered;

    @Column(name = "qty_received", nullable = false, precision = 14, scale = 4)
    private BigDecimal qtyReceived = BigDecimal.ZERO;

    @Column(name = "unit_estimated_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitEstimatedCost;

    /**
     * Packaging as ordered (ADR 0036). {@code qtyOrdered} stays the expanded base quantity;
     * these columns preserve "2 cartons × 24 @ 1,200" for the order, the supplier and history.
     */
    @Column(name = "pack_option_id", length = 36)
    private String packOptionId;

    @Column(name = "purchase_unit", length = 32)
    private String purchaseUnit;

    @Column(name = "purchase_qty", precision = 14, scale = 4)
    private BigDecimal purchaseQty;

    @Column(name = "units_per_purchase_unit", precision = 14, scale = 4)
    private BigDecimal unitsPerPurchaseUnit;

    @Column(name = "pack_price", precision = 14, scale = 4)
    private BigDecimal packPrice;

    @Column(name = "base_unit", length = 16)
    private String baseUnit;

    /** pending | accepted | rejected | partially_accepted */
    @Column(name = "supplier_line_status", nullable = false, length = 32)
    private String supplierLineStatus = "pending";

    @Column(name = "qty_accepted", precision = 14, scale = 4)
    private BigDecimal qtyAccepted;

    @Column(name = "supplier_note", columnDefinition = "TEXT")
    private String supplierNote;

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
