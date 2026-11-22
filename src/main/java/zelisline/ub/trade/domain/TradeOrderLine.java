package zelisline.ub.trade.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A product on a trade order as both shops may see it: name, barcode, pack, quantity, price. */
@Getter
@Setter
@Entity
@Table(name = "trade_order_lines")
public class TradeOrderLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "trade_order_id", nullable = false, length = 36)
    private String tradeOrderId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_name", nullable = false, length = 500)
    private String productName;

    @Column(name = "barcode", length = 191)
    private String barcode;

    @Column(name = "pack_unit", length = 255)
    private String packUnit;

    /** Single units in one of what was ordered (12 for a carton of 12). Null for a single unit. */
    @Column(name = "pack_size", precision = 14, scale = 4)
    private BigDecimal packSize;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    /** What the buying shop says arrived. Null until it has said. */
    @Column(name = "received_qty", precision = 14, scale = 4)
    private BigDecimal receivedQty;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
