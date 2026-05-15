package zelisline.ub.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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
@Table(name = "stock_take_line_batches")
public class StockTakeLineBatch {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false)
    private StockTakeLine line;

    @Column(name = "batch_id", nullable = false, length = 36)
    private String batchId;

    @Column(name = "batch_number", length = 64)
    private String batchNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "system_qty_snapshot", nullable = false, precision = 14, scale = 4)
    private BigDecimal systemQtySnapshot;

    @Column(name = "counted_qty", precision = 14, scale = 4)
    private BigDecimal countedQty;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
