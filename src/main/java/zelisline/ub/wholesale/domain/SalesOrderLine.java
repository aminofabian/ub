package zelisline.ub.wholesale.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One product on a trade order, with the name and price as they were when it was placed. */
@Getter
@Setter
@Entity
@Table(name = "sales_order_lines")
public class SalesOrderLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "item_name", nullable = false, length = 500)
    private String itemName;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "line_index", nullable = false)
    private int lineIndex;

    @Column(name = "status", nullable = false, length = 24)
    private String status = SalesOrderStatuses.LINE_PENDING;

    /** How much of the line the supplier agreed to supply. Null until answered. */
    @Column(name = "accepted_qty", precision = 14, scale = 4)
    private BigDecimal acceptedQty;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
