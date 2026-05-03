package zelisline.ub.inventory.domain;

import java.math.BigDecimal;
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
@Table(name = "stock_transfer_lines")
public class StockTransferLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", nullable = false)
    private StockTransfer transfer;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
