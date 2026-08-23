package zelisline.ub.inventory.domain;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/** One suggested line inside a {@link RestockRun}. */
@Getter
@Setter
@Entity
@Table(
        name = "restock_suggestions",
        uniqueConstraints =
                @UniqueConstraint(name = "uq_restock_suggestion_run_item", columnNames = {"run_id", "item_id"}))
public class RestockSuggestion {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "supplier_id", length = 36)
    private String supplierId;

    /** po | pad */
    @Column(name = "target", nullable = false, length = 8)
    private String target;

    @Column(name = "on_hand", nullable = false, precision = 14, scale = 4)
    private BigDecimal onHand = BigDecimal.ZERO;

    @Column(name = "inbound", nullable = false, precision = 14, scale = 4)
    private BigDecimal inbound = BigDecimal.ZERO;

    @Column(name = "reorder_level", precision = 14, scale = 4)
    private BigDecimal reorderLevel;

    @Column(name = "par", nullable = false, precision = 14, scale = 4)
    private BigDecimal par = BigDecimal.ZERO;

    @Column(name = "suggested_qty", nullable = false, precision = 14, scale = 4)
    private BigDecimal suggestedQty = BigDecimal.ZERO;

    @Column(name = "accepted_qty", precision = 14, scale = 4)
    private BigDecimal acceptedQty;

    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "pack_size", precision = 14, scale = 4)
    private BigDecimal packSize;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(name = "evidence", nullable = false, length = 255)
    private String evidence;

    /** high | medium | low */
    @Column(name = "confidence", nullable = false, length = 16)
    private String confidence;

    /** pending | accepted | snoozed | dismissed */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "pending";

    @Column(name = "snooze_until")
    private LocalDate snoozeUntil;

    @Column(name = "purchase_order_id", length = 36)
    private String purchaseOrderId;

    @Column(name = "order_pad_item_id", length = 36)
    private String orderPadItemId;

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
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
