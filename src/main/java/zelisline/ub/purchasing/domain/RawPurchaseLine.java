package zelisline.ub.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "raw_purchase_lines")
public class RawPurchaseLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "description_text", nullable = false, length = 2000)
    private String descriptionText;

    @Column(name = "amount_money", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountMoney;

    @Column(name = "suggested_item_id", length = 36)
    private String suggestedItemId;

    /** Optional draft qty while session is still open (grocery receive). */
    @Column(name = "draft_qty", precision = 14, scale = 4)
    private BigDecimal draftQty;

    @Column(name = "draft_unit_cost", precision = 14, scale = 4)
    private BigDecimal draftUnitCost;

    @Column(name = "draft_sell_price", precision = 14, scale = 4)
    private BigDecimal draftSellPrice;

    @Column(name = "draft_expiry_date")
    private LocalDate draftExpiryDate;

    @Column(name = "line_status", nullable = false, length = 16)
    private String lineStatus;

    @Column(name = "posted_item_id", length = 36)
    private String postedItemId;

    @Column(name = "usable_qty", precision = 14, scale = 4)
    private BigDecimal usableQty;

    @Column(name = "wastage_qty", precision = 14, scale = 4)
    private BigDecimal wastageQty;

    @Column(name = "inventory_batch_id", length = 36)
    private String inventoryBatchId;

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
}
