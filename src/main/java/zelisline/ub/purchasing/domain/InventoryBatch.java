package zelisline.ub.purchasing.domain;

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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory_batches")
public class InventoryBatch {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    /** Null for opening-balance and stock-gain batches (Phase 3). */
    @Column(name = "supplier_id", length = 36)
    private String supplierId;

    @Column(name = "batch_number", nullable = false, length = 64)
    private String batchNumber;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;

    @Column(name = "initial_quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal initialQuantity;

    @Column(name = "quantity_remaining", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantityRemaining;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "active";

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
