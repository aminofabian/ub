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

/**
 * A SupplyBatch is the HEADER entity for a group of items that arrived
 * together in a single delivery / purchase trip.
 *
 * <p>Each row in this table represents one "batch" as a business user
 * understands it: "Batch #42 — Tuesday market run from Farm Fresh."
 *
 * <p>The individual items within this batch are tracked as
 * {@link zelisline.ub.purchasing.domain.InventoryBatch} rows, each linked back via {@code supplyBatchId}.
 *
 * <p>source_type + source_id link back to the originating document:
 * "path_a_grn" → GoodsReceipt, "path_b_session" → RawPurchaseSession,
 * "opening" → synthetic, "stock_gain" → synthetic, etc.
 */
@Getter
@Setter
@Entity
@Table(name = "supply_batches")
public class SupplyBatch {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    /** Null for opening-balance and stock-gain batches. */
    @Column(name = "supplier_id", length = 36)
    private String supplierId;

    /**
     * Human-readable batch identifier shown everywhere in the UI.
     * Auto-generated like "SB-ABCD1234" but can be overridden by the user.
     */
    @Column(name = "batch_number", nullable = false, length = 64)
    private String batchNumber;

    /**
     * Optional user-facing name: "Tuesday Market Run #7", "Farm Fresh Delivery".
     * Helps users identify batches without looking up batch_number.
     */
    @Column(name = "batch_name", length = 255)
    private String batchName;

    /** Which kind of document created this batch. */
    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    /** ID of the document that created this batch (GoodsReceipt.id, RawPurchaseSession.id, etc.). */
    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;

    /** Total number of distinct items in this batch. */
    @Column(name = "item_count", nullable = false)
    private int itemCount;

    /** Grand total initial quantity across ALL items (in their respective units). */
    @Column(name = "total_initial_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalInitialQuantity;

    /** Total quantity remaining across all items. */
    @Column(name = "total_remaining_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalRemainingQuantity;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "active";

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

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
