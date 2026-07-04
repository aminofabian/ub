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
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import zelisline.ub.inventory.InventoryConstants;

@Getter
@Setter
@Entity
@Table(name = "stock_take_lines")
public class StockTakeLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private StockTakeSession session;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "system_qty_snapshot", nullable = false, precision = 14, scale = 4)
    private BigDecimal systemQtySnapshot;

    @Column(name = "counted_qty", precision = 14, scale = 4)
    private BigDecimal countedQty;

    @Column(name = "admin_quantity", precision = 14, scale = 4)
    private BigDecimal adminQuantity;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "aisle", length = 255)
    private String aisle;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "submitted_by", length = 36)
    private String submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "confirmed_by", length = 36)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = InventoryConstants.DAILY_AUDIT_REVIEW_PENDING;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC, id ASC")
    private java.util.List<StockTakeLineBatch> batches = new java.util.ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
