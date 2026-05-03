package zelisline.ub.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_adjustment_requests")
public class StockAdjustmentRequest {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_take_line_id", nullable = false)
    private StockTakeLine stockTakeLine;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "adjustment_type", nullable = false, length = 32)
    private String adjustmentType;

    @Column(name = "variance_qty", nullable = false, precision = 14, scale = 4)
    private BigDecimal varianceQty;

    @Column(name = "system_qty_snapshot", nullable = false, precision = 14, scale = 4)
    private BigDecimal systemQtySnapshot;

    @Column(name = "counted_qty", nullable = false, precision = 14, scale = 4)
    private BigDecimal countedQty;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
