package zelisline.ub.purchasing.domain;

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

@Getter
@Setter
@Entity
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "goods_receipt_id", nullable = false, length = 36)
    private String goodsReceiptId;

    @Column(name = "purchase_order_line_id", nullable = false, length = 36)
    private String purchaseOrderLineId;

    @Column(name = "qty_received", nullable = false, precision = 14, scale = 4)
    private BigDecimal qtyReceived;

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
