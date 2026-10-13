package zelisline.ub.inventory.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A full-catalog count split into daily batches. Each batch is a regular
 * {@link StockTakeSession} with {@code source = count_cycle}.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_count_cycles")
public class StockCountCycle {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "batch_size", nullable = false)
    private int batchSize;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "counted_items", nullable = false)
    private int countedItems;

    @Column(name = "batches_started", nullable = false)
    private int batchesStarted;

    @Column(name = "active_session_id", length = 36)
    private String activeSessionId;

    @Column(name = "started_by", length = 36)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "cycle_id", nullable = false)
    @OrderBy("sortOrder ASC")
    private List<StockCountCycleItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public void addItem(String itemId, int sortOrder) {
        StockCountCycleItem row = new StockCountCycleItem();
        row.setId(UUID.randomUUID().toString());
        row.setItemId(itemId);
        row.setSortOrder(sortOrder);
        items.add(row);
    }
}
