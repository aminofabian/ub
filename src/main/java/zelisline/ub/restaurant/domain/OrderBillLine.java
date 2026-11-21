package zelisline.ub.restaurant.domain;

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

/** How much of one order line a bill covers: all three beers, or one of them. */
@Getter
@Setter
@Entity
@Table(name = "order_bill_lines")
public class OrderBillLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "bill_id", nullable = false, length = 36)
    private String billId;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "order_line_id", nullable = false, length = 36)
    private String orderLineId;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    /** The order line's price when this bill line was made; null on a bill made before V433. */
    @Column(name = "unit_price", precision = 14, scale = 4)
    private BigDecimal unitPrice;

    /** What this line was charged, so a printed bill never changes after it was shown. */
    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

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
