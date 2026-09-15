package zelisline.ub.storeroom.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One store-room movement — the back-room ops trail.
 *
 * <p>Enums are persisted by {@link EnumType#STRING} name (the module convention);
 * the API exposes lower-case wire values.
 *
 * <p>For a linked product this row sits alongside the inventory ledger entry and
 * points at it via {@link #movementId}, rather than replacing it.
 */
@Getter
@Setter
@Entity
@Table(name = "store_room_movements")
public class StoreRoomMovement {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** Nulled (not cascaded) when the register row is deleted, so history survives. */
    @Column(name = "store_item_id", length = 36)
    private String storeItemId;

    @Column(name = "item_id", length = 36)
    private String itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private StoreRoomDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private StoreRoomReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_effect", nullable = false, length = 16)
    private StoreRoomStockEffect stockEffect;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "note", length = 255)
    private String note;

    /** {@code stock_movements.id} this movement produced, if it moved stock. */
    @Column(name = "movement_id", length = 36)
    private String movementId;

    /** Ledger rows written; a wastage can split across several batches. */
    @Column(name = "movement_count", nullable = false)
    private int movementCount;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

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
