package zelisline.ub.inventory.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_count_cycle_items")
public class StockCountCycleItem {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "cycle_id", nullable = false, length = 36, insertable = false, updatable = false)
    private String cycleId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Batch session the item is (or was) counted in; null while still in the pool. */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "counted_at")
    private Instant countedAt;

    public boolean isCounted() {
        return countedAt != null;
    }

    public boolean isInPool() {
        return countedAt == null && sessionId == null;
    }

    public void returnToPool() {
        sessionId = null;
    }
}
