package zelisline.ub.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An expense (transport, handling, customs, storage, etc.) linked to a specific SupplyBatch.
 * This allows tracking the "extra costs" associated with a delivery beyond the item costs.
 */
@Getter
@Setter
@Entity
@Table(name = "supply_batch_expenses")
public class SupplyBatchExpense {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "supply_batch_id", nullable = false, length = 36)
    private String supplyBatchId;

    /**
     * Category: "transport", "handling", "customs", "storage", "other".
     */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", length = 500)
    private String description;

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
