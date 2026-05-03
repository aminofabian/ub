package zelisline.ub.sales.domain;

import java.math.BigDecimal;
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
@Table(name = "refund_lines")
public class RefundLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "refund_id", nullable = false, length = 36)
    private String refundId;

    @Column(name = "sale_item_id", nullable = false, length = 36)
    private String saleItemId;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
